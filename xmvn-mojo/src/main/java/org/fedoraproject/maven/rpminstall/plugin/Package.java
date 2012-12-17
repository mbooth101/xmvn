/*-
 * Copyright (c) 2012 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fedoraproject.maven.rpminstall.plugin;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.fedoraproject.maven.Configuration;
import org.fedoraproject.maven.utils.FileUtils;

public class Package
    implements Comparable<Package>
{
    private final String suffix;

    private boolean pureDevelPackage = true;

    public Package( String name )
    {
        suffix = name.equals( "" ) ? "" : "-" + name;
    }

    private final FragmentFile depmap = new FragmentFile();

    class TargetFile
    {
        Path sourceFile;

        Path dirPath;

        Path targetName;
    }

    private final List<TargetFile> targetFiles = new LinkedList<>();

    public void addFile( Path file, Path dirPath, Path fileName )
    {
        TargetFile target = new TargetFile();
        target.sourceFile = file;
        target.dirPath = dirPath;
        target.targetName = fileName;
        targetFiles.add( target );
    }

    public void addFile( Path file, Path target )
    {
        addFile( file, target.getParent(), target.getFileName() );
    }

    public void addPomFile( Path file, Path jppGroupId, Path jppArtifactId )
    {
        Path pomName = Paths.get( jppGroupId.toString().replace( '/', '.' ) + "-" + jppArtifactId + ".pom" );
        addFile( file, Configuration.getInstallPomDir(), pomName );
    }

    private static boolean containsNativeCode( Path jar )
    {
        // From /usr/include/linux/elf.h
        final int ELFMAG0 = 0x7F;
        final int ELFMAG1 = 'E';
        final int ELFMAG2 = 'L';
        final int ELFMAG3 = 'F';

        try (ZipInputStream jis = new ZipInputStream( new FileInputStream( jar.toFile() ) ))
        {
            ZipEntry ent;
            while ( ( ent = jis.getNextEntry() ) != null )
            {
                if ( ent.isDirectory() )
                    continue;
                if ( jis.read() == ELFMAG0 && jis.read() == ELFMAG1 && jis.read() == ELFMAG2 && jis.read() == ELFMAG3 )
                    return true;
            }
        }
        catch ( IOException e )
        {
        }

        return false;
    }

    public void addJarFile( Path file, Path baseFile, Collection<Path> symlinks, BigDecimal javaVersion )
        throws IOException
    {
        pureDevelPackage = false;

        Path jarDir = containsNativeCode( file ) ? Configuration.getInstallJniDir() : Configuration.getInstallJarDir();
        addFile( file, jarDir.resolve( baseFile ) );

        for ( Path symlink : symlinks )
        {
            Path target = Paths.get( "/" ).resolve( jarDir ).resolve( baseFile );
            Path symlinkFile = FileUtils.createAnonymousSymlink( target );
            if ( !symlink.isAbsolute() )
                symlink = jarDir.resolve( symlink );
            addFile( symlinkFile, symlink );
        }

        depmap.addJavaVersionRequirement( javaVersion );
    }

    private void installFiles( Installer installer )
        throws IOException
    {
        for ( TargetFile target : targetFiles )
        {
            installer.installFile( target.sourceFile, target.dirPath, target.targetName );
        }
    }

    public void addDepmap( String groupId, String artifactId, String version, Path jppGroupId, Path jppArtifactId )
    {
        depmap.addMapping( groupId, artifactId, version, jppGroupId.toString(), jppArtifactId.toString() );
    }

    public void addRequires( String groupId, String artifactId )
    {
        depmap.addDependency( groupId, artifactId );
    }

    public void addDevelRequires( String groupId, String artifactId )
    {
        depmap.addDevelDependency( groupId, artifactId );
    }

    private void installMetadata( Installer installer )
        throws IOException
    {
        depmap.optimize();

        if ( !depmap.isEmpty() )
        {
            Path file = Files.createTempFile( "xmvn", ".xml" );
            depmap.write( file, pureDevelPackage );
            Path depmapName = Paths.get( Configuration.getInstallName() + suffix + ".xml" );
            addFile( file, Configuration.getInstallDepmapDir(), depmapName );
        }
    }

    private void createFileList()
        throws IOException
    {
        Set<Path> targetNames = new TreeSet<>();
        for ( TargetFile target : targetFiles )
            targetNames.add( target.dirPath.resolve( target.targetName ) );

        try (PrintStream ps = new PrintStream( ".mfiles" + suffix ))
        {
            for ( Path path : targetNames )
                ps.println( "/" + path );
        }
    }

    public void install( Installer installer )
        throws IOException
    {
        installMetadata( installer );
        installFiles( installer );
        createFileList();
    }

    @Override
    public int compareTo( Package rhs )
    {
        return suffix.compareTo( rhs.suffix );
    }
}