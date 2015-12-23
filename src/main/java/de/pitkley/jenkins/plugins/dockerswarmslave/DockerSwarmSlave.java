package de.pitkley.jenkins.plugins.dockerswarmslave;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.ExceptionCatchingThreadFactory;
import hudson.util.LogTaskListener;
import hudson.util.NamingThreadFactory;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.KeyMaterial;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DockerSwarmSlave implements Closeable {

    private static final int START_TIMEOUT = 10;
    private static final int SLAVE_TIMEOUT = 10; // TODO maybe move to system configuration?
    private transient static final ExecutorService executorService = Executors.newCachedThreadPool(new ExceptionCatchingThreadFactory(new NamingThreadFactory(Executors.defaultThreadFactory(), "DockerSwarmSlave.executor")));
    private transient static final Map<AbstractProject<?, ?>, DockerSwarmSlave> DOCKER_SWARM_SLAVE_MAP = new HashMap<AbstractProject<?, ?>, DockerSwarmSlave>();

    private transient final Logger logger = Logger.getLogger(getClass().getName());
    private transient final TaskListener listener = new LogTaskListener(logger, Level.ALL);
    private transient final Jenkins jenkins = Jenkins.getInstance();
    private transient final Computer jenkinsComputer = jenkins.createComputer();
    private transient final Launcher launcher = jenkins.createLauncher(listener);

    private final DockerSwarmSlaveBuildWrapper buildWrapper;
    private final AbstractProject<?, ?> project;
    private final int buildNumber;

    private long timeWaitForStart = -1L;
    private long timeWaitForSlave = -1L;

    private final DockerRegistryEndpoint registryEndpoint;
    private final String dockerExecutable;

    private KeyMaterial dockerEnv;
    private EnvVars envVars;

    private DockerSwarmSlave(DockerSwarmSlaveBuildWrapper buildWrapper, AbstractProject<?, ?> project, int buildNumber) throws IOException, InterruptedException {
        this.buildWrapper = buildWrapper;
        this.project = project;
        this.buildNumber = buildNumber;

        this.registryEndpoint = new DockerRegistryEndpoint(null, buildWrapper.getDockerRegistryCredentials());
        this.dockerExecutable = DockerTool.getExecutable(buildWrapper.getDockerInstallation(), jenkins, null, jenkinsComputer.getEnvironment());

        setupCredentials();
    }

    public static DockerSwarmSlave create(DockerSwarmSlaveBuildWrapper buildWrapper, AbstractProject<?, ?> project, int buildNumber) throws IOException, InterruptedException {
        if (DOCKER_SWARM_SLAVE_MAP.containsKey(project)) {
            throw new IllegalArgumentException("DockerSwarmSlave for project already created, use `DockerSwarmSlave#get()` to get it");
        }

        DockerSwarmSlave dockerSwarmSlave = new DockerSwarmSlave(buildWrapper, project, buildNumber);
        DOCKER_SWARM_SLAVE_MAP.put(project, dockerSwarmSlave);
        return dockerSwarmSlave;
    }

    public static
    @Nullable
    DockerSwarmSlave get(AbstractProject<?, ?> project) {
        return DOCKER_SWARM_SLAVE_MAP.get(project);
    }

    public void setupCredentials() throws IOException, InterruptedException {
        this.dockerEnv = this.buildWrapper.getDockerHost().newKeyMaterialFactory(project, launcher.getChannel())
                .plus(this.registryEndpoint.newKeyMaterialFactory(project, launcher.getChannel()))
                .materialize();
    }

    @Override
    public void close() throws IOException {
        this.cleanup();
        this.dockerEnv.close();
    }

    protected void createSlave() throws IOException, InterruptedException, URISyntaxException {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    EnvVars envVars = getEnvVars();
                    ArgumentListBuilder args;
                    int status;

                    String slaveLabel = getSlaveLabel();

                    // Check if a container with the same label is left-over from a cancelled build or similar
                    // (generally this should not be needed)
                    args = dockerCommand()
                            .add("inspect")
                            .add(slaveLabel);

                    status = launcher.launch()
                            .envs(envVars)
                            .cmds(args)
                            .join();

                    if (status == 0) {
                        // Try to remove the stray container
                        args = dockerCommand()
                                .add("rm", "-f")
                                .add(slaveLabel);

                        launcher.launch()
                                .envs(envVars)
                                .cmds(args)
                                .join();
                    }

                    // Get the master URI for Jenkins
                    String masterUri = getMasterUri();

                    // Run container
                    args = dockerCommand()
                            .add("run", "-d")
                            .add("--name", slaveLabel)
                            .add(buildWrapper.getDockerImage())
                            .add("-master", masterUri)
                            .add("-labels").addQuoted(slaveLabel);

                    // Add specified swarm credentials if applicable
                    String swarmCredentialsId = buildWrapper.getSwarmCredentials();
                    if (swarmCredentialsId != null) {
                        // From what I've seen, we can't use `CredentialsProvider#findCredentialsById` directly, since
                        // we don't have a `Run`-context yet.
                        StandardUsernamePasswordCredentials credentials = CredentialsMatchers.firstOrNull(
                                CredentialsProvider.lookupCredentials(
                                        StandardUsernamePasswordCredentials.class,
                                        project,
                                        null,
                                        Collections.<DomainRequirement>emptyList()
                                ),
                                CredentialsMatchers.withId(swarmCredentialsId)
                        );

                        if (credentials == null) {
                            // This shouldn't happen since the user specified the credentials using a list in the job
                            // configuration. One way it could happen though is that the job was e.g. imported.
                            throw new RuntimeException("Swarm credentials ID seems to be ambiguous");
                        }

                        // Do NOT use `addQuoted` in the following statements.
                        // I'm not sure if either `Launcher` will automatically supply quotes or if it is an issue with
                        // the `swarm-client.jar`, but at least up until version 2.0 the swarm-client would include the
                        // quotes and fail to authenticate.
                        args
                                .add("-username").add(credentials.getUsername())
                                .add("-password").addMasked(credentials.getPassword());
                    }

                    status = launcher.launch()
                            .envs(envVars)
                            .cmds(args)
                            .stderr(listener.getLogger())
                            .join();

                    if (status != 0) {
                        throw new RuntimeException("Launching the docker-swarm-slave failed, aborting.");
                    }

                    // Set the start time for a potential timeout
                    setTimeWaitForSlave(System.currentTimeMillis());
                } catch (Exception e) {
                    DockerSwarmSlaveAbortHelper.abortBuild(project, e);
                }
            }
        });
        this.timeWaitForStart = System.currentTimeMillis();
    }

    protected void stopSlave() throws IOException, InterruptedException {
        // Run this in a separate thread as not to block Jenkins
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    EnvVars envVars = getEnvVars();
                    ArgumentListBuilder args;

                    String slaveLabel = getSlaveLabel();
                    // Allow the container to stop gracefully, Docker will SIGKILL it with a default timeout of 10 seconds if it doesn't stop on it's own
                    args = dockerCommand()
                            .add("stop")
                            .add(slaveLabel);
                    launcher.launch()
                            .envs(envVars)
                            .cmds(args)
                            .join();

                    // Remove the container (it is guaranteed to be stopped by `docker stop`)
                    args = dockerCommand()
                            .add("rm")
                            .add(slaveLabel);
                    launcher.launch()
                            .envs(envVars)
                            .cmds(args)
                            .start();
                } catch (Exception e) {
                    e.printStackTrace(listener.error("Failed to stop and remove the docker-container"));
                }
            }
        });
    }

    protected void destroySlave() {
        try {
            EnvVars envVars = getEnvVars();
            ArgumentListBuilder args;

            String slaveLabel = getSlaveLabel();

            // Force-remove the docker-container
            // If this fails there is nothing we could do, thus we ignore any non-zero exit-code
            args = dockerCommand()
                    .add("rm", "-f")
                    .add(slaveLabel);
            launcher.launch()
                    .envs(envVars)
                    .cmds(args)
                    .start(); // We do not `join()` here, as we do not want to block Jenkins waiting for the removal
        } catch (Exception e) {
            // If destroying it causes an exception, we can generally ignore it, but we'll output it anyway
            e.printStackTrace(listener.error("Failed to desstroy the docker-container"));
        }
    }

    private void setTimeWaitForSlave(long timeWaitForSlave) {
        this.timeWaitForSlave = timeWaitForSlave;
    }

    protected boolean shouldTimeout() {
        if (this.timeWaitForSlave != -1) {
            return (System.currentTimeMillis() - this.timeWaitForSlave) / 1000 > SLAVE_TIMEOUT;
        }
        //noinspection SimplifiableIfStatement (for clarity)
        if (this.timeWaitForStart != -1) {
            return (System.currentTimeMillis() - this.timeWaitForStart) / 1000 > START_TIMEOUT;
        }
        return false;
    }

    private EnvVars getEnvVars() throws IOException, InterruptedException {
        if (envVars == null) {
            envVars = new EnvVars(jenkinsComputer.getEnvironment()).overrideAll(dockerEnv.env());
        }

        return envVars;
    }

    public String getSlaveLabel() {
        return "dss-" + project.getName().hashCode() + "-" + buildNumber;
    }

    private ArgumentListBuilder dockerCommand() {
        ArgumentListBuilder args = new ArgumentListBuilder();
        for (String s : dockerCommandArgs()) {
            args.add(s);
        }

        return args;
    }

    private List<String> dockerCommandArgs() {
        List<String> args = new ArrayList<String>();
        args.add(this.dockerExecutable);
        if (buildWrapper.getDockerHost().getUri() != null) {
            args.add("-H");
            args.add(buildWrapper.getDockerHost().getUri());
        }

        return args;
    }

    protected String getMasterUri() throws URISyntaxException {
        String jenkinsUri = buildWrapper.getJenkinsUri();
        if (jenkinsUri != null && !jenkinsUri.isEmpty()) {
            return jenkinsUri;
        }

        String rootUri = Jenkins.getInstance().getRootUrl();
        if (rootUri == null) {
            throw new RuntimeException("Unable to get the Jenkins URL, it needs to be set in the job or in the global configuration.");
        }

        return rootUri;
    }

    protected void cleanup() {
        try {
            if (DockerSwarmSlaveAbortHelper.shouldAbortBuild(project)) {
                this.destroySlave();
            } else {
                this.stopSlave();
            }
        } catch (Exception ignored) {
        }
        //noinspection ThrowableResultOfMethodCallIgnored
        DockerSwarmSlaveAbortHelper.remove(project);
        DOCKER_SWARM_SLAVE_MAP.remove(project);
    }
}
