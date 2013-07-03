/*-
 * Copyright (c) 2013 Red Hat, Inc.
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
package org.fedoraproject.maven.tools.subst;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.fedoraproject.maven.model.Artifact;
import org.fedoraproject.maven.resolver.ResolutionRequest;
import org.fedoraproject.maven.resolver.Resolver;
import org.fedoraproject.maven.utils.FileUtils;

@Component( role = ArtifactVisitor.class )
public class ArtifactVisitor
    implements FileVisitor<Path>
{
    private final Set<String> types = new TreeSet<>();

    @Requirement
    private Logger logger;

    @Requirement
    private Resolver resolver;

    private boolean followSymlinks;

    public void setTypes( Collection<String> types )
    {
        this.types.addAll( types );
    }

    public void setFollowSymlinks( boolean followSymlinks )
    {
        this.followSymlinks = followSymlinks;
    }

    @Override
    public FileVisitResult postVisitDirectory( Path path, IOException e )
        throws IOException
    {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory( Path path, BasicFileAttributes attrs )
        throws IOException
    {
        if ( Files.isSymbolicLink( path ) && !followSymlinks )
        {
            logger.debug( "Skipping symlink to directory: " + path );
            return FileVisitResult.SKIP_SUBTREE;
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile( Path path, BasicFileAttributes attrs )
        throws IOException
    {
        if ( !Files.isRegularFile( path ) )
        {
            logger.debug( "Skipping " + path + ": not a regular file" );
            return FileVisitResult.CONTINUE;
        }

        String fileName = path.getFileName().toString();

        for ( String type : types )
        {
            if ( fileName.endsWith( "." + type ) )
            {
                substituteArtifact( path, type );
            }
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed( Path path, IOException e )
        throws IOException
    {
        logger.warn( "Failed to access file: ", e );
        return FileVisitResult.CONTINUE;
    }

    private Artifact readArtifactDefinition( Path path, String extension )
    {
        try (ZipInputStream zis = new ZipInputStream( new FileInputStream( path.toFile() ) ))
        {
            ZipEntry entry;
            while ( ( entry = zis.getNextEntry() ) != null )
            {
                String name = entry.getName();
                if ( name.startsWith( "META-INF/maven/" ) && name.endsWith( "/pom.properties" ) )
                {
                    Properties properties = new Properties();
                    properties.load( zis );

                    String groupId = properties.getProperty( "groupId" );
                    String artifactId = properties.getProperty( "artifactId" );
                    String version = properties.getProperty( "version" );
                    return new Artifact( groupId, artifactId, version, extension );
                }
            }

            logger.info( "Skipping file " + path + ": No artifact definition found" );
            return null;
        }
        catch ( IOException e )
        {
            logger.error( "Failed to get artifact definition from file " + path, e );
            return null;
        }
    }

    private void substituteArtifact( Path path, String type )
        throws IOException
    {
        Artifact artifact = readArtifactDefinition( path, type );
        if ( artifact == null )
            return;

        File artifactFile = resolver.resolve( new ResolutionRequest( artifact ) ).getArtifactFile();
        if ( artifactFile == null )
        {
            logger.warn( "Skipping file " + path + ": Artifact " + artifact + " not found in repository" );
            return;
        }

        Path target = artifactFile.toPath();
        FileUtils.replaceFileWithSymlink( path, target );
        logger.info( "Linked " + path + " to " + target );
    }
}