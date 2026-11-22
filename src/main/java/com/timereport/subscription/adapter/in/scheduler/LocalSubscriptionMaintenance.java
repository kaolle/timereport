package com.timereport.subscription.adapter.in.scheduler;

import com.timereport.subscription.application.port.in.MaintainSubscriptions;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class LocalSubscriptionMaintenance {
    private static final Logger LOG = Logger.getLogger(LocalSubscriptionMaintenance.class);
    private final MaintainSubscriptions maintenance;

    public LocalSubscriptionMaintenance(MaintainSubscriptions maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(cron = "${subscription.maintenance-cron:off}", timeZone = "Europe/Stockholm", concurrentExecution = SKIP)
    void maintain() {
        try {
            maintenance.run();
        } catch (RuntimeException failure) {
            LOG.error("Subscription maintenance failed; next scheduled run will retry");
        }
    }
}
