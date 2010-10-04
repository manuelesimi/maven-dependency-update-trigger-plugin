/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc. Olivier Lamy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jvnet.hudson.plugins.mavendepsupdate;

import static hudson.Util.fixNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.PluginFirstClassLoader;
import hudson.PluginWrapper;
import hudson.model.BuildableItem;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Run;
import hudson.scheduler.CronTabList;
import hudson.tasks.Builder;
import hudson.tasks.Maven;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import antlr.ANTLRException;

/**
 * @author Olivier Lamy
 */
public class MavenDependencyUpdateTrigger
    extends Trigger<BuildableItem>
{

    private static final Logger LOGGER = Logger.getLogger( MavenDependencyUpdateTrigger.class.getName() );

    private final boolean checkPlugins;

    @DataBoundConstructor
    public MavenDependencyUpdateTrigger( String cron_value, boolean checkPlugins )
        throws ANTLRException
    {
        super( cron_value );
        this.checkPlugins = checkPlugins;
    }

    @Override
    public void run()
    {
        ProjectBuildingRequest projectBuildingRequest = null;

        Node node = super.job.getLastBuiltOn();

        if ( node == null )
        {
            // FIXME schedule the first buid ??
            //job.scheduleBuild( arg0, arg1 )
            LOGGER.info( "no previous build found so skip maven update trigger" );
            return;
        }

        ClassLoader origClassLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            PluginWrapper pluginWrapper = Hudson.getInstance().getPluginManager()
                .getPlugin( "maven-dependency-update-trigger" );

            FilePath mavenShadedJar = node.getRootPath()
                .child( MavenDependencyUpdateTriggerComputerListener.MAVEN_SHADED_JAR_NAME + ".jar" );

            boolean isMaster = node == Hudson.getInstance();

            if ( isMaster )
            {
                mavenShadedJar = node.getRootPath().child( "plugins" ).child( "maven-dependency-update-trigger" )
                    .child( MavenDependencyUpdateTriggerComputerListener.MAVEN_SHADED_JAR_NAME + ".jar" );
            }

            AbstractProject<?, ?> abstractProject = (AbstractProject<?, ?>) super.job;

            FilePath workspace = node.getWorkspaceFor( (TopLevelItem) super.job );

            FilePath moduleRoot = abstractProject.getScm().getModuleRoot( workspace );

            Run run = abstractProject.getLastBuild();
            //FIXME check -f from cli or rootPOM from MavenModuleSet
            String rootPomPath = moduleRoot.getRemote() + "/pom.xml";

            String localRepoPath = getLocalRepo( workspace ).toString();

            String projectWorkspace = moduleRoot.getRemote();

            MavenUpdateChecker checker = new MavenUpdateChecker( mavenShadedJar, rootPomPath, localRepoPath,
                                                                 this.checkPlugins, projectWorkspace, isMaster );
            if ( isMaster )
            {
                checker.setClassLoaderParent( (PluginFirstClassLoader) pluginWrapper.classLoader );
            }

            LOGGER.info( "run MavenUpdateChecker on node " + node.getDisplayName() );

            MavenUpdateCheckerResult mavenUpdateCheckerResult = node.getChannel().call( checker );

            LOGGER.info( "run MavenUpdateChecker on node " + node.getDisplayName() + " done " );

            if ( mavenUpdateCheckerResult.getFileUpdatedNames().size() > 0 )
            {
                LOGGER.info( "snapshotDownloaded so triggering a new build" );
                job.scheduleBuild( 0,
                                   new MavenDependencyUpdateTriggerCause( mavenUpdateCheckerResult
                                       .getFileUpdatedNames() ) );
            }

        }
        catch ( Exception e )
        {
            LOGGER.warning( "ignore " + e.getMessage() );
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( origClassLoader );
        }
    }

    private File getLocalRepo( FilePath workspace )
    {
        boolean usePrivateRepo = usePrivateRepo();
        if ( usePrivateRepo )
        {
            return new File( workspace.getRemote(), ".repository" );
        }
        return RepositorySystem.defaultUserLocalRepository;
    }

    @Extension
    public static class DescriptorImpl
        extends TriggerDescriptor
    {
        public boolean isApplicable( Item item )
        {
            return item instanceof BuildableItem;
        }

        public String getDisplayName()
        {
            return Messages.plugin_title();
        }

        @Override
        public String getHelpFile()
        {
            return "/plugin/maven-dependency-update-trigger/help.html";
        }

        /**
         * Performs syntax check.
         */
        public FormValidation doCheck( @QueryParameter String value )
        {
            try
            {
                String msg = CronTabList.create( fixNull( value ) ).checkSanity();
                if ( msg != null )
                    return FormValidation.warning( msg );
                return FormValidation.ok();
            }
            catch ( ANTLRException e )
            {
                return FormValidation.error( e.getMessage() );
            }
        }
    }

    public static class MavenDependencyUpdateTriggerCause
        extends Cause
    {
        private List<String> snapshotsDownloaded;

        MavenDependencyUpdateTriggerCause( List<String> snapshotsDownloaded )
        {
            this.snapshotsDownloaded = snapshotsDownloaded;
        }

        @Override
        public String getShortDescription()
        {
            StringBuilder sb = new StringBuilder( "maven SNAPSHOT dependency update cause : " );
            if ( snapshotsDownloaded != null && snapshotsDownloaded.size() > 0 )
            {
                sb.append( " " );
                for ( String snapshot : snapshotsDownloaded )
                {
                    sb.append( snapshot );
                }
                sb.append( " " );
            }

            return sb.toString();
        }

        @Override
        public boolean equals( Object o )
        {
            return o instanceof MavenDependencyUpdateTriggerCause;
        }

        @Override
        public int hashCode()
        {
            return 5 * 2;
        }
    }

    private boolean usePrivateRepo()
    {
        // check if FreeStyleProject
        if ( this.job instanceof FreeStyleProject )
        {
            FreeStyleProject fp = (FreeStyleProject) this.job;
            for ( Builder b : fp.getBuilders() )
            {
                if ( b instanceof Maven )
                {
                    if ( ( (Maven) b ).usePrivateRepository )
                    {
                        return true;
                    }
                }
            }
            return false;
        }
        // check if there is a method called usesPrivateRepository
        try
        {
            Method method = this.job.getClass().getMethod( "usesPrivateRepository", null );
            Boolean bool = (Boolean) method.invoke( this.job, null );
            return bool.booleanValue();
        }
        catch ( SecurityException e )
        {
            LOGGER.warning( "ignore " + e.getMessage() );
        }
        catch ( NoSuchMethodException e )
        {
            LOGGER.warning( "ignore " + e.getMessage() );
        }
        catch ( IllegalArgumentException e )
        {
            LOGGER.warning( "ignore " + e.getMessage() );
        }
        catch ( IllegalAccessException e )
        {
            LOGGER.warning( "ignore " + e.getMessage() );
        }
        catch ( InvocationTargetException e )
        {
            LOGGER.warning( "ignore " + e.getMessage() );
        }

        return false;
    }

    private Map<String, MavenProject> getProjectMap( List<MavenProject> projects )
    {
        Map<String, MavenProject> index = new LinkedHashMap<String, MavenProject>();

        for ( MavenProject project : projects )
        {
            String projectId = ArtifactUtils.key( project.getGroupId(), project.getArtifactId(), project.getVersion() );
            index.put( projectId, project );
        }

        return index;
    }

}
