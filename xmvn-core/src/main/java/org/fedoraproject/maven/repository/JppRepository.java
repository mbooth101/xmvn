/*-
 * Copyright (c) 2012-2013 Red Hat, Inc.
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
package org.fedoraproject.maven.repository;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.codehaus.plexus.component.annotations.Component;

/**
 * JPP-style repository JPP layout, either versioned or versionless, depending on properties.
 * <p>
 * Example: {@code g/r/o/u/p/artifact-ver.ext} or {@code g/r/o/u/p/artifact.ext}
 * 
 * @author Mikolaj Izdebski
 */
@Component( role = Repository.class, hint = JppRepository.ROLE_HINT, instantiationStrategy = "per-lookup" )
public class JppRepository
    extends SimpleRepository
{
    static final String ROLE_HINT = "jpp";

    @Override
    protected Path getArtifactPath( String groupId, String artifactId, String version, String extension,
                                    boolean versionless )
    {
        StringBuilder path = new StringBuilder();

        if ( groupId.startsWith( "JPP/" ) )
            groupId = groupId.substring( 4 );
        else if ( groupId.equals( "JPP" ) )
            groupId = null;

        if ( groupId != null )
        {
            path.append( groupId );
            path.append( '/' );
        }

        path.append( artifactId );

        if ( !versionless )
        {
            path.append( '-' );
            path.append( version );
        }

        path.append( '.' );
        path.append( extension );

        return Paths.get( path.toString() );
    }
}
