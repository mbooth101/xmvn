/*-
 * Copyright (c) 2014 Red Hat, Inc.
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
package org.fedoraproject.xmvn.tools.install.impl;

import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;
import static org.custommonkey.xmlunit.XMLUnit.setIgnoreWhitespace;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.sisu.launch.InjectedTest;
import org.junit.After;
import org.junit.Before;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Mikolaj Izdebski
 */
public abstract class AbstractFileTest
    extends InjectedTest
{
    private final List<File> files = new ArrayList<>();

    protected Path workdir;

    protected Path installRoot;

    private final List<String> descriptors = new ArrayList<>();

    protected void add( File file )
        throws Exception
    {
        files.add( file );
    }

    @Before
    public void setUpWorkdir()
        throws IOException
    {
        String testName = getClass().getName();
        Path workPath = Paths.get( "target" ).resolve( "test-work" );
        Files.createDirectories( workPath );
        workdir = Files.createTempDirectory( workPath, testName );
        installRoot = workdir.resolve( "install-root" );
        Files.createDirectory( installRoot );
    }

    @After
    public void tearDownWorkdir()
        throws IOException
    {
        Files.walkFileTree( workdir, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs )
                throws IOException
            {
                Files.delete( file );
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory( Path dir, IOException exc )
                throws IOException
            {
                Files.delete( dir );
                return FileVisitResult.CONTINUE;
            }
        } );
    }

    protected Path performInstallation()
        throws Exception
    {
        try
        {
            for ( File file : files )
                file.install( installRoot );

            for ( File file : files )
                descriptors.add( file.getDescriptor() );

            return installRoot;
        }
        finally
        {
            files.clear();
        }
    }

    protected void assertDirectoryStructure( String... expected )
        throws Exception
    {
        assertDirectoryStructure( installRoot, expected );
    }

    protected void assertDirectoryStructure( Path root, String... expected )
        throws Exception
    {
        List<String> actualList = new ArrayList<>();
        Files.walkFileTree( root, new FileSystemWalker( root, actualList ) );
        assertEqualsImpl( actualList, "directory structure", expected );
    }

    protected void assertDescriptorEquals( String... expected )
    {
        assertEqualsImpl( descriptors, "file descriptor", expected );
    }

    private void assertEqualsImpl( List<String> actualList, String what, String... expected )
    {
        List<String> expectedList = new ArrayList<>();
        for ( String string : expected )
            expectedList.add( string );

        Collections.sort( expectedList );
        Collections.sort( actualList );

        try
        {
            Iterator<String> expectedIterator = expectedList.iterator();
            Iterator<String> actualIterator = actualList.iterator();

            while ( expectedIterator.hasNext() && actualIterator.hasNext() )
                assertEquals( expectedIterator.next(), actualIterator.next() );

            assertFalse( expectedIterator.hasNext() );
            assertFalse( actualIterator.hasNext() );
        }
        catch ( AssertionError e )
        {
            System.err.println( "EXPECTED " + what + ":" );
            for ( String string : expectedList )
                System.err.println( "  " + string );

            System.err.println( "ACTUAL " + what + ":" );
            for ( String string : actualList )
                System.err.println( "  " + string );

            throw e;
        }
    }

    Path getResource( String name )
    {
        return Paths.get( "src/test/resources/", name ).toAbsolutePath();
    }

    void assertFilesEqual( Path expected, Path actual )
        throws IOException
    {
        byte expectedContent[] = Files.readAllBytes( expected );
        byte actualContent[] = Files.readAllBytes( actual );
        assertTrue( Arrays.equals( expectedContent, actualContent ) );
    }

    protected void assertDescriptorEquals( Path mfiles, String... expected )
        throws IOException
    {
        List<String> lines = Files.readAllLines( mfiles, Charset.defaultCharset() );

        assertEqualsImpl( lines, "descriptor", expected );
    }

    protected void assertDescriptorEquals( Package pkg, String... expected )
        throws IOException
    {
        Path mfiles = installRoot.resolve( ".mfiles" );
        pkg.writeDescriptor( mfiles );
        assertDescriptorEquals( mfiles, expected );
    }

    private void unifyUuids( NodeList nodes )
    {
        for ( int i = 0; i < nodes.getLength(); i++ )
        {
            nodes.item( i ).setTextContent( "uuid-placeholder" );
        }
    }

    protected void assertMetadataEqual( Path expected, Path actual )
        throws Exception
    {
        setIgnoreWhitespace( true );
        assertTrue( Files.isRegularFile( actual ) );
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document expectedXml = builder.parse( expected.toString() );
        Document actualXml = builder.parse( actual.toString() );

        NodeList nodes = expectedXml.getElementsByTagName( "path" );

        for ( int i = 0; i < nodes.getLength(); i++ )
        {
            Node pathNode = nodes.item( i );
            String path = pathNode.getTextContent();
            if ( path.startsWith( "???" ) )
                pathNode.setTextContent( getResource( path.substring( 3 ) ).toAbsolutePath().toString() );
        }

        unifyUuids( expectedXml.getElementsByTagName( "uuid" ) );
        unifyUuids( actualXml.getElementsByTagName( "uuid" ) );

        assertXMLEqual( expectedXml, actualXml );
    }
}