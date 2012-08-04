package org.apache.archiva.dependency.tree.maven2;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


import org.apache.archiva.admin.model.RepositoryAdminException;
import org.apache.archiva.admin.model.beans.ManagedRepository;
import org.apache.archiva.admin.model.beans.NetworkProxy;
import org.apache.archiva.admin.model.beans.ProxyConnector;
import org.apache.archiva.admin.model.beans.RemoteRepository;
import org.apache.archiva.admin.model.managed.ManagedRepositoryAdmin;
import org.apache.archiva.admin.model.networkproxy.NetworkProxyAdmin;
import org.apache.archiva.admin.model.proxyconnector.ProxyConnectorAdmin;
import org.apache.archiva.admin.model.remote.RemoteRepositoryAdmin;
import org.apache.archiva.common.plexusbridge.PlexusSisuBridge;
import org.apache.archiva.common.plexusbridge.PlexusSisuBridgeException;
import org.apache.archiva.metadata.repository.storage.RepositoryPathTranslator;
import org.apache.archiva.proxy.common.WagonFactory;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.repository.internal.DefaultArtifactDescriptorReader;
import org.apache.maven.repository.internal.DefaultVersionRangeResolver;
import org.apache.maven.repository.internal.DefaultVersionResolver;
import org.apache.maven.repository.internal.MavenRepositorySystemSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.collection.CollectRequest;
import org.sonatype.aether.collection.CollectResult;
import org.sonatype.aether.collection.DependencyCollectionException;
import org.sonatype.aether.collection.DependencySelector;
import org.sonatype.aether.graph.Dependency;
import org.sonatype.aether.graph.DependencyVisitor;
import org.sonatype.aether.impl.ArtifactDescriptorReader;
import org.sonatype.aether.impl.VersionRangeResolver;
import org.sonatype.aether.impl.VersionResolver;
import org.sonatype.aether.impl.internal.DefaultServiceLocator;
import org.sonatype.aether.repository.LocalRepository;
import org.sonatype.aether.spi.connector.RepositoryConnectorFactory;
import org.sonatype.aether.util.artifact.DefaultArtifact;
import org.sonatype.aether.util.graph.selector.AndDependencySelector;
import org.sonatype.aether.util.graph.selector.ExclusionDependencySelector;
import org.sonatype.aether.util.graph.selector.OptionalDependencySelector;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Olivier Lamy
 * @since 1.4-M3
 */
@Service( "dependencyTreeBuilder#maven3" )
public class Maven3DependencyTreeBuilder
{
    private Logger log = LoggerFactory.getLogger( getClass() );

    @Inject
    private PlexusSisuBridge plexusSisuBridge;

    @Inject
    @Named( value = "repositoryPathTranslator#maven2" )
    private RepositoryPathTranslator pathTranslator;

    @Inject
    private WagonFactory wagonFactory;

    @Inject
    private ManagedRepositoryAdmin managedRepositoryAdmin;

    @Inject
    private ProxyConnectorAdmin proxyConnectorAdmin;

    @Inject
    private NetworkProxyAdmin networkProxyAdmin;

    @Inject
    private RemoteRepositoryAdmin remoteRepositoryAdmin;

    private ArtifactFactory factory;

    private ModelBuilder builder;


    private RepositorySystem repoSystem;

    @PostConstruct
    public void initialize()
        throws PlexusSisuBridgeException
    {
        factory = plexusSisuBridge.lookup( ArtifactFactory.class, "default" );

        repoSystem = plexusSisuBridge.lookup( RepositorySystem.class );
        DefaultModelBuilderFactory defaultModelBuilderFactory = new DefaultModelBuilderFactory();
        builder = defaultModelBuilderFactory.newInstance();
    }

    public void buildDependencyTree( List<String> repositoryIds, String groupId, String artifactId, String version,
                                     DependencyVisitor dependencyVisitor )
        throws Exception
    {
        Artifact projectArtifact = factory.createProjectArtifact( groupId, artifactId, version );
        ManagedRepository repository = null;
        try
        {
            repository = findArtifactInRepositories( repositoryIds, projectArtifact );
        }
        catch ( RepositoryAdminException e )
        {
            // FIXME better exception
            throw new Exception( "Cannot build project dependency tree " + e.getMessage(), e );
        }

        if ( repository == null )
        {
            // metadata could not be resolved
            return;
        }

        // MRM-1411
        // TODO: this is a workaround for a lack of proxy capability in the resolvers - replace when it can all be
        //       handled there. It doesn't cache anything locally!
        List<RemoteRepository> remoteRepositories = new ArrayList<RemoteRepository>();
        Map<String, NetworkProxy> networkProxies = new HashMap<String, NetworkProxy>();

        Map<String, List<ProxyConnector>> proxyConnectorsMap = proxyConnectorAdmin.getProxyConnectorAsMap();
        List<ProxyConnector> proxyConnectors = proxyConnectorsMap.get( repository.getId() );
        if ( proxyConnectors != null )
        {
            for ( ProxyConnector proxyConnector : proxyConnectors )
            {
                remoteRepositories.add( remoteRepositoryAdmin.getRemoteRepository( proxyConnector.getTargetRepoId() ) );

                NetworkProxy networkProxyConfig = networkProxyAdmin.getNetworkProxy( proxyConnector.getProxyId() );

                if ( networkProxyConfig != null )
                {
                    // key/value: remote repo ID/proxy info
                    networkProxies.put( proxyConnector.getTargetRepoId(), networkProxyConfig );
                }
            }
        }

        // FIXME take care of relative path
        resolve( repository.getLocation(), groupId, artifactId, version, dependencyVisitor );

    }


    private void resolve( String localRepoDir, String groupId, String artifactId, String version,
                          DependencyVisitor dependencyVisitor )
    {

        RepositorySystem system = newRepositorySystem();

        RepositorySystemSession session = newRepositorySystemSession( system, localRepoDir );

        org.sonatype.aether.artifact.Artifact artifact =
            new DefaultArtifact( groupId + ":" + artifactId + ":" + version );

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot( new Dependency( artifact, "" ) );

        // add remote repositories ?
        //collectRequest.addRepository(  )

        collectRequest.setRequestContext( "project" );

        //collectRequest.addRepository( repo );

        try
        {
            CollectResult collectResult = system.collectDependencies( session, collectRequest );
            collectResult.getRoot().accept( dependencyVisitor );
            log.debug( "test" );
        }
        catch ( DependencyCollectionException e )
        {
            log.error( e.getMessage(), e );
        }


    }

    public static RepositorySystem newRepositorySystem()
    {
        DefaultServiceLocator locator = new DefaultServiceLocator();
        locator.addService( RepositoryConnectorFactory.class,
                            ArchivaRepositoryConnectorFactory.class );// FileRepositoryConnectorFactory.class );
        locator.addService( VersionResolver.class, DefaultVersionResolver.class );
        locator.addService( VersionRangeResolver.class, DefaultVersionRangeResolver.class );
        locator.addService( ArtifactDescriptorReader.class, DefaultArtifactDescriptorReader.class );
        //locator.addService( RepositoryConnectorFactory.class, WagonRepositoryConnectorFactory.class );
        //locator.setServices( WagonProvider.class,  );

        return locator.getService( RepositorySystem.class );
    }

    public static RepositorySystemSession newRepositorySystemSession( RepositorySystem system, String localRepoDir )
    {
        MavenRepositorySystemSession session = new MavenRepositorySystemSession();

        DependencySelector depFilter = new AndDependencySelector( new ExclusionDependencySelector() );
        session.setDependencySelector( depFilter );

        LocalRepository localRepo = new LocalRepository( localRepoDir );
        session.setLocalRepositoryManager( system.newLocalRepositoryManager( localRepo ) );

        //session.setTransferListener(  );
        //session.setRepositoryListener( n );

        return session;
    }


    private ManagedRepository findArtifactInRepositories( List<String> repositoryIds, Artifact projectArtifact )
        throws RepositoryAdminException
    {
        for ( String repoId : repositoryIds )
        {
            ManagedRepository managedRepository = managedRepositoryAdmin.getManagedRepository( repoId );

            File repoDir = new File( managedRepository.getLocation() );
            File file = pathTranslator.toFile( repoDir, projectArtifact.getGroupId(), projectArtifact.getArtifactId(),
                                               projectArtifact.getBaseVersion(),
                                               projectArtifact.getArtifactId() + "-" + projectArtifact.getVersion()
                                                   + ".pom" );

            if ( file.exists() )
            {
                return managedRepository;
            }
        }
        return null;
    }


}
