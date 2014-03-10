/*-
 * Copyright (c) 2013-2014 Red Hat, Inc.
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
package org.fedoraproject.xmvn.connector.ivy;

import static org.fedoraproject.xmvn.connector.ivy.IvyResolver.LazyDeployerProvider.deployer;
import static org.fedoraproject.xmvn.connector.ivy.IvyResolver.LazyResolverProvider.resolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ivy.core.cache.ArtifactOrigin;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.DownloadReport;
import org.apache.ivy.core.report.DownloadStatus;
import org.apache.ivy.core.report.MetadataArtifactDownloadReport;
import org.apache.ivy.core.resolve.DownloadOptions;
import org.apache.ivy.core.resolve.ResolveData;
import org.apache.ivy.core.resolve.ResolvedModuleRevision;
import org.apache.ivy.plugins.parser.ModuleDescriptorParser;
import org.apache.ivy.plugins.parser.m2.PomModuleDescriptorParser;
import org.apache.ivy.plugins.parser.m2.PomModuleDescriptorWriter;
import org.apache.ivy.plugins.parser.m2.PomWriterOptions;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorParser;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter;
import org.apache.ivy.plugins.repository.Resource;
import org.apache.ivy.plugins.repository.file.FileRepository;
import org.apache.ivy.plugins.repository.file.FileResource;
import org.apache.ivy.plugins.resolver.AbstractResolver;
import org.apache.ivy.plugins.resolver.util.ResolvedResource;

import org.fedoraproject.xmvn.deployer.Deployer;
import org.fedoraproject.xmvn.deployer.DeploymentRequest;
import org.fedoraproject.xmvn.deployer.DeploymentResult;
import org.fedoraproject.xmvn.locator.IsolatedDeployer;
import org.fedoraproject.xmvn.locator.IsolatedResolver;
import org.fedoraproject.xmvn.locator.IsolatedXMvnServiceLocator;
import org.fedoraproject.xmvn.locator.XMvnHomeClassLoader;
import org.fedoraproject.xmvn.resolver.ResolutionRequest;
import org.fedoraproject.xmvn.resolver.ResolutionResult;
import org.fedoraproject.xmvn.resolver.Resolver;

/**
 * Resolve and publish Ivy artifacts by delegating most tasks to XMvn.
 * 
 * @author Mikolaj Izdebski
 */
public class IvyResolver
    extends AbstractResolver
{
    static class LazyLocatorProvider
    {
        static final IsolatedXMvnServiceLocator locator;

        static
        {
            @SuppressWarnings( "resource" ) XMvnHomeClassLoader realm =
                new XMvnHomeClassLoader( LazyLocatorProvider.class.getClassLoader() );
            realm.addJarDirectory( realm.getHome().resolve( "lib" ).resolve( "ivy" ) );
            locator = new IsolatedXMvnServiceLocator( realm );
        }
    }

    static class LazyResolverProvider
    {
        static final Resolver resolver = new IsolatedResolver( LazyLocatorProvider.locator );
    }

    static class LazyDeployerProvider
    {
        static final Deployer deployer = new IsolatedDeployer( LazyLocatorProvider.locator );
    }

    public IvyResolver()
    {
        setName( "XMvn" );
    }

    private static org.eclipse.aether.artifact.Artifact ivy2aether( ModuleRevisionId revision, String extension )
    {
        String groupId = revision.getOrganisation();
        String artifactId = revision.getName();
        String classifier = revision.getExtraAttribute( "classifier" );
        String version = revision.getRevision();

        return new org.eclipse.aether.artifact.DefaultArtifact( groupId, artifactId, classifier, extension, version );
    }

    private static org.eclipse.aether.artifact.Artifact ivy2aether( org.apache.ivy.core.module.descriptor.Artifact artifact )
    {
        ModuleRevisionId revision = artifact.getModuleRevisionId();

        String groupId = revision.getOrganisation();
        String artifactId = revision.getName();
        String extension = artifact.getExt();
        String classifier = revision.getExtraAttribute( "classifier" );
        String version = revision.getRevision();
        String type = artifact.getType();

        if ( classifier == null || classifier.isEmpty() )
        {
            if ( extension.equals( "jar" ) && type.equals( "source" ) )
                classifier = "sources";
            else if ( extension.equals( "jar" ) && type.equals( "javadoc" ) )
                classifier = "javadoc";
        }

        return new org.eclipse.aether.artifact.DefaultArtifact( groupId, artifactId, classifier, extension, version );
    }

    private static String resolvedVersion( ResolutionResult resolutionResult )
    {
        String version = resolutionResult.getCompatVersion();
        return version != null ? version : "SYSTEM";
    }

    private String resolveModuleVersion( ModuleDescriptor module )
    {
        for ( Artifact artifact : module.getAllArtifacts() )
        {
            ResolutionRequest request = new ResolutionRequest( ivy2aether( artifact ) );
            ResolutionResult result = resolver.resolve( request );
            if ( result.getArtifactFile() != null )
                return resolvedVersion( result );
        }

        return null;
    }

    private ModuleDescriptor readIvyModuleDescriptorFromPom( DependencyDescriptor depDescriptor )
        throws IOException, ParseException
    {
        ModuleRevisionId depId = depDescriptor.getDependencyRevisionId();

        ResolutionRequest request = new ResolutionRequest();
        request.setArtifact( ivy2aether( depId, "pom" ) );
        ResolutionResult result = resolver.resolve( request );
        File pomPath = result.getArtifactFile();

        String version;
        ModuleDescriptor module;
        if ( pomPath != null )
        {
            ModuleDescriptorParser parser = PomModuleDescriptorParser.getInstance();
            module = parser.parseDescriptor( getSettings(), pomPath.toURI().toURL(), false );
            version = resolvedVersion( result );
        }
        else
        {
            module = DefaultModuleDescriptor.newDefaultInstance( depId, depDescriptor.getAllDependencyArtifacts() );
            version = resolveModuleVersion( module );
            if ( version == null )
                return null;
        }

        module.setResolvedModuleRevisionId( ModuleRevisionId.newInstance( depId, version ) );
        return module;
    }

    @Override
    public ResolvedModuleRevision getDependency( DependencyDescriptor systemDd, ResolveData data )
        throws ParseException
    {
        try
        {
            ModuleDescriptor module = readIvyModuleDescriptorFromPom( systemDd );
            if ( module == null )
                return null;

            MetadataArtifactDownloadReport report = new MetadataArtifactDownloadReport( module.getMetadataArtifact() );
            report.setDownloadStatus( DownloadStatus.NO );
            report.setSearched( true );

            return new ResolvedModuleRevision( this, this, module, report, true );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    @Override
    public ResolvedResource findIvyFileRef( DependencyDescriptor depDescriptor, ResolveData data )
    {
        Artifact artifact = DefaultArtifact.newIvyArtifact( depDescriptor.getDependencyRevisionId(), null );

        ResolutionRequest request = new ResolutionRequest();
        request.setArtifact( ivy2aether( artifact.getModuleRevisionId(), "pom" ) );
        ResolutionResult result = resolver.resolve( request );
        File pomPath = result.getArtifactFile();
        if ( pomPath == null )
            return null;

        try
        {
            File ivyPath = Files.createTempFile( "xmvn-", ".ivy.xml" ).toFile();
            ModuleDescriptorParser parser = PomModuleDescriptorParser.getInstance();
            ModuleDescriptor module = parser.parseDescriptor( getSettings(), pomPath.toURI().toURL(), false );
            XmlModuleDescriptorWriter.write( module, ivyPath );
        }
        catch ( IOException | ParseException e )
        {
            throw new RuntimeException( e );
        }

        Resource fileResource = new FileResource( new FileRepository(), pomPath );
        return new ResolvedResource( fileResource, resolvedVersion( result ) );
    }

    @Override
    public DownloadReport download( Artifact[] artifacts, DownloadOptions options )
    {
        DownloadReport report = new DownloadReport();

        for ( Artifact artifact : artifacts )
        {
            ArtifactDownloadReport artifactReport = new ArtifactDownloadReport( artifact );
            ResolutionRequest request = new ResolutionRequest();
            request.setArtifact( ivy2aether( artifact ) );
            ResolutionResult result = resolver.resolve( request );
            File artifactPath = result.getArtifactFile();

            if ( artifactPath != null )
            {
                artifactReport.setArtifactOrigin( new ArtifactOrigin( artifact, false, artifactPath.toString() ) );
                artifactReport.setLocalFile( artifactPath );
                artifactReport.setDownloadStatus( DownloadStatus.SUCCESSFUL );
            }
            else
            {
                artifactReport.setDownloadStatus( DownloadStatus.FAILED );
            }

            report.addArtifactReport( artifactReport );
        }

        return report;
    }

    private void deploy( org.eclipse.aether.artifact.Artifact artifact, File artifactFile )
        throws IOException
    {
        DeploymentRequest request = new DeploymentRequest();
        request.setArtifact( artifact.setFile( artifactFile ) );
        DeploymentResult result = deployer.deploy( request );
        if ( result.getException() != null )
            throw new IOException( "Failed to publish artifact", result.getException() );
    }

    private void deployEffectivePom( ModuleRevisionId moduleRevisionId, File artifactFile )
        throws IOException
    {
        try
        {
            File pomFile = Files.createTempFile( "xmvn-", ".pom" ).toFile();
            ModuleDescriptorParser parser = XmlModuleDescriptorParser.getInstance();
            ModuleDescriptor module = parser.parseDescriptor( getSettings(), artifactFile.toURI().toURL(), false );
            PomModuleDescriptorWriter.write( module, pomFile, new PomWriterOptions() );

            org.eclipse.aether.artifact.Artifact artifact = ivy2aether( moduleRevisionId, "pom" );
            Map<String, String> properties = new LinkedHashMap<>( artifact.getProperties() );
            properties.put( "xmvn.artifact.stereotype", "effective" );
            deploy( artifact.setProperties( properties ), artifactFile );
        }
        catch ( ParseException e )
        {
            throw new IOException( e );
        }
    }

    @Override
    public void publish( Artifact artifact, File artifactFile, boolean overwrite )
        throws IOException
    {
        if ( artifact.getExt().equals( "xml" ) && artifact.getType().equals( "ivy" ) )
        {
            deployEffectivePom( artifact.getModuleRevisionId(), artifactFile );
        }

        deploy( ivy2aether( artifact ), artifactFile );
    }
}
