package de.pitkley.jenkins.plugins.dockerswarmslave;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;
import jenkins.authentication.tokens.api.AuthenticationTokens;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.util.Collections;

public class DockerSwarmSlaveBuildWrapper extends BuildWrapper {

    private final String dockerImage;
    private final String swarmCredentials;
    private final DockerServerEndpoint dockerHost;
    private final String dockerInstallation;
    private final String dockerNetwork;
    private final String dockerRegistryCredentials;

    @DataBoundConstructor
    public DockerSwarmSlaveBuildWrapper(String dockerImage, String swarmCredentials, DockerServerEndpoint dockerHost, String dockerInstallation, String dockerNetwork, String dockerRegistryCredentials) {
        this.dockerImage = dockerImage;
        this.swarmCredentials = swarmCredentials;
        this.dockerHost = dockerHost;
        this.dockerInstallation = dockerInstallation;
        this.dockerNetwork = dockerNetwork;
        this.dockerRegistryCredentials = dockerRegistryCredentials;
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        AbstractProject<?, ?> project = build.getProject();

        if (DockerSwarmSlaveAbortHelper.shouldAbortBuild(project)) {
            //noinspection ThrowableResultOfMethodCallIgnored
            DockerSwarmSlaveAbortHelper.getAbortBuildCause(project).printStackTrace(listener.getLogger());
            return null;
        }

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                return true;
            }
        };
    }

    public String getDockerImage() {
        return dockerImage;
    }

    public String getSwarmCredentials() {
        return swarmCredentials;
    }

    public DockerServerEndpoint getDockerHost() {
        return dockerHost;
    }

    public String getDockerInstallation() {
        return dockerInstallation;
    }

    public String getDockerNetwork() {
        return dockerNetwork;
    }

    public String getDockerRegistryCredentials() {
        return dockerRegistryCredentials;
    }

    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public boolean isApplicable(AbstractProject<?, ?> abstractProject) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Build with docker-swarm-slave";
        }

        @SuppressWarnings("unused")
        public ListBoxModel doFillSwarmCredentialsItems(@AncestorInPath Item item, @QueryParameter String uri) {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withAll(CredentialsProvider.lookupCredentials(
                            StandardUsernamePasswordCredentials.class,
                            item,
                            null,
                            Collections.<DomainRequirement>emptyList()
                    ));
        }

        @SuppressWarnings("unused")
        public ListBoxModel doFillDockerRegistryCredentialsItems(@AncestorInPath Item item, @QueryParameter String uri) {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(AuthenticationTokens.matcher(DockerRegistryToken.class),
                            CredentialsProvider.lookupCredentials(
                                    StandardCredentials.class,
                                    item,
                                    null,
                                    Collections.<DomainRequirement>emptyList()
                            ));
        }
    }
}
