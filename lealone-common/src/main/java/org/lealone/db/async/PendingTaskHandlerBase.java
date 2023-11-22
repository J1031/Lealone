/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.db.async;

import org.lealone.db.link.LinkableList;
import org.lealone.db.scheduler.Scheduler;
import org.lealone.db.scheduler.SchedulerThread;

public abstract class PendingTaskHandlerBase implements PendingTaskHandler {

    protected final LinkableList<PendingTask> pendingTasks = new LinkableList<>();
    protected Scheduler scheduler;

    @Override
    public Scheduler getScheduler() {
        return scheduler;
    }

    @Override
    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void submitTask(AsyncTask task) {
        if (scheduler != null && SchedulerThread.currentScheduler() == scheduler) {
            task.run();
        } else {
            addPendingTask(new PendingTask(task));
            // getScheduler().submitTask(this, task);
            if (pendingTasks.size() > 1)
                removeCompletedTasks();
        }
    }

    @Override
    public void submitTask(PendingTask task) {
        addPendingTask(task);
    }

    private void addPendingTask(PendingTask pt) {
        pendingTasks.add(pt);
        scheduler.wakeUp();
    }

    private void removeCompletedTasks() {
        if (pendingTasks.isEmpty())
            return;
        PendingTask pt = pendingTasks.getHead();
        while (pt != null && pt.isCompleted()) {
            pt = pt.getNext();
            pendingTasks.decrementSize();
            pendingTasks.setHead(pt);
        }
        if (pendingTasks.getHead() == null)
            pendingTasks.setTail(null);
    }

    @Override
    public PendingTask getPendingTask() {
        return pendingTasks.getHead();
    }
}