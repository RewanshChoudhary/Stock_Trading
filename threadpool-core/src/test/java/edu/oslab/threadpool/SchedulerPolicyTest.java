package edu.oslab.threadpool;

import edu.oslab.threadpool.scheduler.FcfsScheduler;
import edu.oslab.threadpool.scheduler.MlfqScheduler;
import edu.oslab.threadpool.scheduler.PriorityScheduler;
import edu.oslab.threadpool.scheduler.RoundRobinScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulerPolicyTest {
    @Test
    void fcfsSelectsInArrivalOrder() {
        FcfsScheduler scheduler = new FcfsScheduler();
        Task first = task("first", 0);
        Task second = task("second", 0);
        scheduler.offer(first, 0);
        scheduler.offer(second, 0);

        assertEquals(first, scheduler.poll(1));
        assertEquals(second, scheduler.poll(1));
    }

    @Test
    void priorityAgingEventuallyPromotesAWaitingLowPriorityTask() {
        PriorityScheduler scheduler = new PriorityScheduler(Duration.ofNanos(10), 25);
        Task lowPriority = task("waiting", 0);
        Task highPriority = task("newcomer", 10);
        scheduler.offer(lowPriority, 0);
        scheduler.offer(highPriority, 190);

        assertEquals(lowPriority, scheduler.poll(200));
    }

    @Test
    void roundRobinMovesAnUnfinishedTaskToTheTail() {
        RoundRobinScheduler scheduler = new RoundRobinScheduler(5);
        Task first = task("first", 0);
        Task second = task("second", 0);
        scheduler.offer(first, 0);
        scheduler.offer(second, 0);

        assertEquals(first, scheduler.poll(1));
        scheduler.requeueAfterYield(first, 2);
        assertEquals(second, scheduler.poll(3));
        assertEquals(first, scheduler.poll(4));
    }

    @Test
    void mlfqDemotesAfterYieldAndPeriodicallyBoostsWaitingTasks() {
        MlfqScheduler scheduler = new MlfqScheduler(new long[]{2, 4, 8}, Duration.ofNanos(50));
        Task first = task("first", 0);
        Task second = task("second", 0);
        scheduler.offer(first, 0);
        scheduler.offer(second, 0);

        assertEquals(first, scheduler.poll(1));
        scheduler.requeueAfterYield(first, 2);
        assertEquals(1, scheduler.levelOf(first));
        assertEquals(second, scheduler.poll(3));
        scheduler.requeueAfterYield(second, 4);
        assertEquals(first, scheduler.poll(5));
        scheduler.requeueAfterYield(first, 6);
        assertEquals(2, scheduler.levelOf(first));

        assertEquals(second, scheduler.poll(7));
        scheduler.requeueAfterYield(second, 8);
        assertEquals(first, scheduler.poll(51));
        assertEquals(0, scheduler.levelOf(first));
        assertEquals(0, scheduler.levelOf(second));
    }

    private static Task task(String id, int priority) {
        return new Task(new TaskSpec(id, priority, 1, Set.of(), quantum -> SliceResult.done()));
    }
}
