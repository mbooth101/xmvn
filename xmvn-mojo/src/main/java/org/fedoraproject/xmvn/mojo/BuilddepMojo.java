/*-
 * Copyright (c) 2012-2015 Red Hat, Inc.
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
package org.fedoraproject.xmvn.mojo;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.lifecycle.mapping.Lifecycle;
import org.apache.maven.lifecycle.mapping.LifecycleMapping;
import org.apache.maven.lifecycle.mapping.LifecycleMojo;
import org.apache.maven.lifecycle.mapping.LifecyclePhase;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.MXSerializer;
import org.codehaus.plexus.util.xml.pull.XmlSerializer;
import org.fedoraproject.xmvn.artifact.Artifact;
import org.fedoraproject.xmvn.artifact.DefaultArtifact;
import org.fedoraproject.xmvn.model.ModelProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Mikolaj Izdebski
 */
@Mojo( name = "builddep", aggregator = true, requiresDependencyResolution = ResolutionScope.NONE )
@Named
public class BuilddepMojo
    extends AbstractMojo
{
    static class NamespacedArtifact
    {
        String namespace;

        Artifact artifact;

        public NamespacedArtifact( String namespace, Artifact artifact )
        {
            this.namespace = namespace != null ? namespace : "";
            this.artifact = artifact;
        }

        @Override
        public int hashCode()
        {
            return artifact.hashCode() ^ namespace.hashCode();
        }

        @Override
        public boolean equals( Object rhs )
        {
            NamespacedArtifact other = (NamespacedArtifact) rhs;
            return namespace.equals( other.namespace ) && artifact.equals( other.artifact );
        }
    }

    private final Logger logger = LoggerFactory.getLogger( BuilddepMojo.class );

    @Parameter( defaultValue = "xmvn.builddep.skip" )
    private boolean skip;

    @Parameter( defaultValue = "${reactorProjects}", readonly = true, required = true )
    private List<MavenProject> reactorProjects;

    @Inject
    private Map<String, LifecycleMapping> lifecycleMappings;

    private final ModelProcessor modelProcessor;

    // Injected through reflection by XMvn lifecycle participant
    private List<String[]> resolutions;

    @Inject
    public BuilddepMojo( ModelProcessor modelProcessor )
    {
        this.modelProcessor = modelProcessor;
    }

    private static void addOptionalChild( Xpp3Dom parent, String tag, String value, String defaultValue )
    {
        if ( defaultValue == null || !value.equals( defaultValue ) )
        {
            Xpp3Dom child = new Xpp3Dom( tag );
            child.setValue( value );
            parent.addChild( child );
        }
    }

    private static Xpp3Dom toXpp3Dom( NamespacedArtifact namespacedArtifact, String tag )
    {
        Artifact artifact = namespacedArtifact.artifact;
        Xpp3Dom parent = new Xpp3Dom( tag );

        addOptionalChild( parent, "namespace", namespacedArtifact.namespace, "" );
        addOptionalChild( parent, "groupId", artifact.getGroupId(), null );
        addOptionalChild( parent, "artifactId", artifact.getArtifactId(), null );
        addOptionalChild( parent, "extension", artifact.getExtension(), "jar" );
        addOptionalChild( parent, "classifier", artifact.getClassifier(), "" );
        addOptionalChild( parent, "version", artifact.getVersion(), "SYSTEM" );

        return parent;
    }

    private static void serialize( NamespacedArtifact artifact, XmlSerializer serializer, String namespace, String tag )
        throws IOException
    {
        Xpp3Dom dom = toXpp3Dom( artifact, tag );
        dom.writeToSerializer( namespace, serializer );
    }

    @Override
    public void execute()
        throws MojoExecutionException
    {
        if ( skip )
        {
            logger.info( "Skipping buiddep: xmvn.builddep.skip property was set" );
            return;
        }

        if ( resolutions == null )
        {
            logger.warn( "Skipping buiddep: XMvn lifecycle participant is absent" );
            return;
        }

        Set<Artifact> artifacts = new LinkedHashSet<>();
        for ( MavenProject project : reactorProjects )
        {
            Model model = project.getModel().clone();
            String modelId = model.getLocation( "" ).getSource().getModelId();
            BuildDependencyVisitor visitor = new BuildDependencyVisitor( modelId );
            modelProcessor.processModel( model, visitor );
            artifacts.addAll( visitor.getArtifacts() );

            String packaging = project.getPackaging() != null ? project.getPackaging() : "jar";
            LifecycleMapping lifecycleMapping = lifecycleMappings.get( packaging );
            Lifecycle defaultLifecycle = lifecycleMapping.getLifecycles().get( "default" );
            for ( LifecyclePhase phase : defaultLifecycle.getLifecyclePhases().values() )
            {
                for ( LifecycleMojo mojo : phase.getMojos() )
                {
                    String goal = mojo.getGoal();
                    String[] coords = goal.split( ":" );
                    if ( coords.length == 4 )
                    {
                        artifacts.add( new DefaultArtifact( coords[0], coords[1], coords[2] ) );
                    }
                }
            }
        }

        Set<NamespacedArtifact> deps = new LinkedHashSet<>();
        for ( String[] resolution : resolutions )
        {
            if ( resolution == null )
                continue;

            Artifact artifact = new DefaultArtifact( resolution[0] );
            String compatVersion = resolution[1];
            String namespace = resolution[2];

            if ( artifacts.contains( artifact ) )
            {
                deps.add( new NamespacedArtifact( namespace, artifact.setVersion( compatVersion ) ) );
            }
        }

        // Remove common deps
        {
            Set<Artifact> COMMON_PLUGINS = new LinkedHashSet<>();

            // FIXME: don't hardcode this

            // Default lifecycle mappings for packaging "jar"
            COMMON_PLUGINS.add( new DefaultArtifact( "org.apache.maven.plugins", "maven-resources-plugin" ) );
            COMMON_PLUGINS.add( new DefaultArtifact( "org.apache.maven.plugins", "maven-compiler-plugin" ) );
            COMMON_PLUGINS.add( new DefaultArtifact( "org.apache.maven.plugins", "maven-surefire-plugin" ) );
            COMMON_PLUGINS.add( new DefaultArtifact( "org.apache.maven.plugins", "maven-jar-plugin" ) );

            // Called by XMvn directly
            COMMON_PLUGINS.add( new DefaultArtifact( "org.apache.maven.plugins", "maven-javadoc-plugin" ) );

            deps.removeIf( dep -> COMMON_PLUGINS.contains( dep.artifact.setVersion( Artifact.DEFAULT_VERSION ) ) );
        }

        serializeArtifacts( deps );
    }

    private void serializeArtifacts( Set<NamespacedArtifact> artifacts )
        throws MojoExecutionException
    {
        try (Writer writer = Files.newBufferedWriter( Paths.get( ".xmvn-builddep" ), StandardCharsets.UTF_8 ))
        {
            XmlSerializer s = new MXSerializer();
            s.setProperty( "http://xmlpull.org/v1/doc/properties.html#serializer-indentation", "  " );
            s.setProperty( "http://xmlpull.org/v1/doc/properties.html#serializer-line-separator", "\n" );
            s.setOutput( writer );
            s.startDocument( "US-ASCII", null );
            s.comment( " Build dependencies generated by XMvn " );
            s.text( "\n" );
            s.startTag( null, "dependencies" );

            for ( NamespacedArtifact dependencyArtifact : artifacts )
                serialize( dependencyArtifact, s, null, "dependency" );

            s.endTag( null, "dependencies" );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Unable to write builddep file", e );
        }
    }
}
