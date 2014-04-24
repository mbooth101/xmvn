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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;

import com.google.inject.Binder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.fedoraproject.xmvn.artifact.Artifact;
import org.fedoraproject.xmvn.artifact.DefaultArtifact;
import org.fedoraproject.xmvn.config.PackagingRule;
import org.fedoraproject.xmvn.metadata.ArtifactAlias;
import org.fedoraproject.xmvn.metadata.ArtifactMetadata;
import org.fedoraproject.xmvn.metadata.PackageMetadata;
import org.fedoraproject.xmvn.repository.Repository;
import org.fedoraproject.xmvn.repository.RepositoryConfigurator;
import org.fedoraproject.xmvn.repository.RepositoryPath;

/**
 * @author Michael Simacek
 */
public class DefaultArtifactInstallerTest
        extends AbstractFileTest
{
    private final Repository repositoryMock = createMock( Repository.class );
    private final RepositoryPath repositoryPathMock = createMock( RepositoryPath.class );
    private final RepositoryConfigurator repositoryConfiguratorMock = createMock( RepositoryConfigurator.class );

    @Inject
    private ArtifactInstaller installer;

    @Override
    public void configure( Binder binder )
    {
        binder.bind( RepositoryConfigurator.class ).toInstance( repositoryConfiguratorMock );
    }

    @Before
    public void setUpMocks()
    {
        Artifact inputArtifact = new DefaultArtifact( "com.example", "test", "4.5" );
        expect( repositoryPathMock.getPath() ).andReturn( Paths.get( "com.example-test" ) );
        expect( repositoryPathMock.getRepository() ).andReturn( repositoryMock );
        expect( repositoryMock.getPrimaryArtifactPath( inputArtifact ) ).andReturn( repositoryPathMock );
        expect( repositoryMock.getNamespace() ).andReturn( "ns" );
        expect( repositoryConfiguratorMock.configureRepository( "install" ) ).andReturn( repositoryMock );
        replay( repositoryPathMock );
        replay( repositoryMock );
        replay( repositoryConfiguratorMock );
    }

    @After
    public void tearDownMocks()
    {
        verify( repositoryConfiguratorMock );
        verify( repositoryMock );
        verify( repositoryPathMock );
    }

    private ArtifactMetadata createArtifact()
    {
        ArtifactMetadata artifact = new ArtifactMetadata();
        artifact.setGroupId( "com.example" );
        artifact.setArtifactId( "test" );
        artifact.setVersion( "4.5" );
        artifact.setPath( getResource( "example.jar" ).toString() );
        return artifact;
    }

    @Test
    public void testInstallation()
        throws Exception
    {
        ArtifactMetadata artifact = createArtifact();
        Path metadataPath = Paths.get( "usr/share/maven-metadata/test.xml" );
        JavaPackage pkg = new JavaPackage( "test", metadataPath );
        PackagingRule rule = new PackagingRule();

        installer.install( pkg, artifact, rule );

        PackageMetadata metadata = pkg.getMetadata();
        assertEquals( 1, metadata.getArtifacts().size() );
        ArtifactMetadata actualArtifact = metadata.getArtifacts().get( 0 );
        assertEquals( "ns", actualArtifact.getNamespace() );
        assertNotNull( actualArtifact.getUuid() );

        assertEquals( 2, pkg.getFiles().size() );
        Iterator<File> iterator = pkg.getFiles().iterator();
        File file = iterator.next();
        if ( file.getTargetPath().equals( metadataPath ) )
            file = iterator.next();
        assertEquals( Paths.get( "com.example-test" ), file.getTargetPath() );
        assertEquals( "/com.example-test", artifact.getPath() );
    }

    @Test
    public void testCompatVersion()
        throws Exception
    {
        ArtifactMetadata artifact = createArtifact();
        JavaPackage pkg = new JavaPackage( "test", Paths.get( "usr/share/maven-metadata/test.xml" ) );
        PackagingRule rule = new PackagingRule();
        rule.addVersion( "3.4" );
        rule.addVersion( "3" );

        installer.install( pkg, artifact, rule );

        PackageMetadata metadata = pkg.getMetadata();
        assertEquals( 1, metadata.getArtifacts().size() );
        ArtifactMetadata actualArtifact = metadata.getArtifacts().get( 0 );
        List<String> actualVersions = actualArtifact.getCompatVersions();
        Collections.sort( actualVersions );
        assertEquals( Arrays.asList( new String[] { "3", "3.4" } ), actualVersions );
    }

    @Test
    public void testAliases()
        throws Exception
    {
        ArtifactMetadata artifact = createArtifact();
        JavaPackage pkg = new JavaPackage( "test", Paths.get( "usr/share/maven-metadata/test.xml" ) );
        PackagingRule rule = new PackagingRule();

        org.fedoraproject.xmvn.config.Artifact alias1 = new org.fedoraproject.xmvn.config.Artifact();
        alias1.setGroupId( "com.example" );
        alias1.setArtifactId( "alias1" );
        alias1.setVersion( "3.4" );
        org.fedoraproject.xmvn.config.Artifact alias2 = new org.fedoraproject.xmvn.config.Artifact();
        alias2.setGroupId( "com.example" );
        alias2.setArtifactId( "alias2" );
        alias2.setClassifier( "war" );
        rule.addAlias( alias1 );
        rule.addAlias( alias2 );

        installer.install( pkg, artifact, rule );

        PackageMetadata metadata = pkg.getMetadata();
        assertEquals( 1, metadata.getArtifacts().size() );
        ArtifactMetadata actualArtifact = metadata.getArtifacts().get( 0 );
        List<ArtifactAlias> actualAliases = actualArtifact.getAliases();
        assertEquals( 2, actualAliases.size() );
        assertEquals( "com.example", actualAliases.get( 0 ).getGroupId() );
        assertEquals( "alias1", actualAliases.get( 0 ).getArtifactId() );
        assertEquals( "com.example", actualAliases.get( 1 ).getGroupId() );
        assertEquals( "alias2", actualAliases.get( 1 ).getArtifactId() );
        assertEquals( "war", actualAliases.get( 1 ).getClassifier() );
    }
}