package de.pitkley.jenkins.plugins.dockerswarmslave;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.LogTaskListener;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.KeyMaterial;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DockerSwarmSlave implements Closeable {

    private static final int SLAVE_TIMEOUT = 10;

    private transient final Logger logger = Logger.getLogger(getClass().getName());
    private final TaskListener listener = new LogTaskListener(logger, Level.ALL);

    private transient final Computer master = Computer.currentComputer();
    private transient final Launcher launcher = master.getNode().createLauncher(listener);

    private final DockerSwarmSlaveBuildWrapper buildWrapper;
    private final AbstractProject<?, ?> project;
    private final long timeStarted;

    private final DockerRegistryEndpoint registryEndpoint;
    private final String dockerExecutable;

    private KeyMaterial dockerEnv;
    private EnvVars envVars;

    public DockerSwarmSlave(DockerSwarmSlaveBuildWrapper buildWrapper, AbstractProject<?, ?> project, long timeStarted) throws IOException, InterruptedException {
        this.buildWrapper = buildWrapper;
        this.project = project;
        this.timeStarted = timeStarted;

        this.registryEndpoint = new DockerRegistryEndpoint(null, buildWrapper.getDockerRegistryCredentials());
        this.dockerExecutable = DockerTool.getExecutable(buildWrapper.getDockerInstallation(), master.getNode(), null, master.getEnvironment());

        setupCredentials();
    }

    public static DockerSwarmSlave create(DockerSwarmSlaveBuildWrapper buildWrapper, AbstractProject<?, ?> project) throws IOException, InterruptedException {
        DockerSwarmSlave dockerSwarmSlave = new DockerSwarmSlave(buildWrapper, project, System.currentTimeMillis());
        buildWrapper.setDockerSwarmSlave(dockerSwarmSlave);
        return dockerSwarmSlave;
    }

    public void setupCredentials() throws IOException, InterruptedException {
        this.dockerEnv = this.buildWrapper.getDockerHost().newKeyMaterialFactory(project, launcher.getChannel())
                .plus(this.registryEndpoint.newKeyMaterialFactory(project, launcher.getChannel()))
                .materialize();
    }

    @Override
    public void close() throws IOException {
        this.dockerEnv.close();
    }

    public void start() throws IOException, InterruptedException {
        EnvVars envVars = getEnvVars();
        ArgumentListBuilder args;
        int status;

        String slaveName = getSlaveName();

        // Remove container that might be left-over from a cancelled build or similar
        // (generally this should not be needed)
        args = dockerCommand()
                .add("rm", "-f")
                .add(slaveName);

        launcher.launch()
                .envs(envVars)
                .cmds(args)
                .join();

        // Get master IP (routed from the docker container)
        String masterIp = getMasterIp();

        // Run container
        args = dockerCommand()
                .add("run", "-d")
                .add("--name", slaveName)
                .add(buildWrapper.getDockerImage())
                .add("-master", "http://" + masterIp + ":8080/jenkins/" /* FIXME get path from config! */);

        status = launcher.launch()
                .envs(envVars)
                .cmds(args)
                .stderr(listener.getLogger()).join();
        logger.info("[START] status launch container: " + status);

        if (status != 0) {
            logger.info("[START] throw runtimeexception");
            throw new RuntimeException("Launching the docker-swarm-slave failed, aborting.");
        }
    }

    public void stop() throws IOException, InterruptedException {
        EnvVars envVars = getEnvVars();
        ArgumentListBuilder args;
        int status;

        String slaveName = getSlaveName();
        args = dockerCommand()
                .add("rm", "-f")
                .add(slaveName);

        launcher.launch()
                .envs(envVars)
                .cmds(args)
                .join();
    }

    public boolean shouldTimeout() {
        return (System.currentTimeMillis() - this.timeStarted) / 1000 > SLAVE_TIMEOUT;
    }

    private EnvVars getEnvVars() throws IOException, InterruptedException {
        if (envVars == null) {
            envVars = new EnvVars(master.getEnvironment()).overrideAll(dockerEnv.env());
        }

        return envVars;
    }

    public String getSlaveName() {
        return "dss-" + project.getName().hashCode() + "-" + project.getNextBuildNumber();
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

    public String getMasterIp() throws IOException, InterruptedException {
        // Check if we are in a docker container
        int status = launcher.launch()
                .cmds("cat", "/.dockerinit")
                .join();

        if (status == 1) {
            // .dockerinit doesn't exist, we are most probably not in a docker-container.
            // One could determine it further using '/proc/self/cgroups', but we are not going to.

            // Get docker bridge gateway
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ArgumentListBuilder args = dockerCommand()
                    .add("network", "inspect", buildWrapper.getDockerNetwork());

            status = launcher.launch()
                    .cmds(args)
                    .stdout(out).join();

            if (status != 0) {
                throw new RuntimeException("Docker network '" + buildWrapper.getDockerNetwork() + "' not found, aborting.");
            }

            String inspect = out.toString("UTF-8").trim();
            Matcher m = Pattern.compile(".Gateway.:\\s*.(([0-9]{0,3}\\.?){4}).").matcher(inspect);
            if (!m.find()) {
                throw new RuntimeException("Couldn't determine master IP, aborting.");
            }
            return m.group(1);
        } else {
            // .dockerinit exists, we are probably in a docker-container

            // Get hostname
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            status = launcher.launch()
                    .cmds("hostname")
                    .stdout(out).join();

            if (status != 0) {
                throw new RuntimeException("Can't get hostname, aborting.");
            }

            String hostname = out.toString("UTF-8").trim();

            // Get hosts file
            out = new ByteArrayOutputStream();
            status = launcher.launch()
                    .cmds("grep", "-m 1", hostname, "/etc/hosts")
                    .stdout(out).join();

            if (status != 0) {
                throw new RuntimeException("Couldn't get hosts-file, aborting.");
            }

            String hostIp = out.toString("UTF-8").trim();
            if (hostIp.length() > 0) {
                throw new RuntimeException("Couldn't get hosts-file entry, aborting.");
            }
            return hostIp;
        }
    }
}
