package org.jenkinsci.plugins.vsphere;

import java.io.IOException;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.InvisibleAction;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import static java.util.logging.Level.WARNING;

public final class FixedLifespanCloudRetentionStrategy extends RetentionStrategy<AbstractCloudComputer> {
    @Restricted(NoExternalUse.class)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    private static final Logger LOGGER = Logger.getLogger(CloudRetentionStrategy.class.getName());
    private final int lifespanMinutes;
    private transient boolean atEndOfLife;

    @DataBoundConstructor
    public FixedLifespanCloudRetentionStrategy(int lifespanMinutes) {
        this.lifespanMinutes = lifespanMinutes;
    }

    private synchronized boolean isAtEndOfLife() {
        return atEndOfLife;
    }

    private synchronized void setAtEndOfLife() {
        atEndOfLife = true;
    }

    public int getLifespanMinutes() {
        return lifespanMinutes;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void start(AbstractCloudComputer c) {
        c.addAction(new CloudComputerCreatedOnInvisibleAction(System.currentTimeMillis()));
        super.start(c);
        c.connect(false);
    }

    /**
     * This method will be called periodically to allow this strategy to decide what to do with it's owning slave.
     *
     * @param c {@link Computer} for which this strategy is assigned. This computer may be online or offline.
     *          This object also exposes a bunch of properties that the callee can use to decide what action to take.
     * @return The number of minutes after which the strategy would like to be checked again. The strategy may be
     * rechecked earlier or later that this!
     */
    @Override
    public long check(AbstractCloudComputer c) {

        final long creationTimeMillis = c.getAction(CloudComputerCreatedOnInvisibleAction.class).getCreationTimeMillis();
        final long ageMilliseconds = System.currentTimeMillis() - creationTimeMillis;

        if (!isAtEndOfLife() && ageMilliseconds > Duration.ofMinutes(lifespanMinutes).toMillis()) {
            LOGGER.log(Level.INFO, "Lifespan of {0} reached.  Will terminate once idle.", c.getName());
            setAtEndOfLife();
        }
        if (isAtEndOfLife() && c.isIdle()) {
            final AbstractCloudSlave computerNode = c.getNode();
            if (computerNode != null) {
                try {
                    computerNode.terminate();
                } catch (InterruptedException | IOException e) {
                    LOGGER.log(WARNING, "Failed to terminate " + c.getName(), e);
                }
            }
        }
        return 1;
    }

    @Override
    public boolean isAcceptingTasks(AbstractCloudComputer c) {
        return !isAtEndOfLife();
    }

    public static final class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "vSphere Fixed Lifespan Retention Strategy";
        }
    }

    public static class CloudComputerCreatedOnInvisibleAction extends InvisibleAction {
        private final long creationTimeMillis;

        CloudComputerCreatedOnInvisibleAction(long creationTimeMillis) {
            this.creationTimeMillis = creationTimeMillis;
        }

        long getCreationTimeMillis() {
            return creationTimeMillis;
        }
    }
}
