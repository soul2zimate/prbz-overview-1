/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.overview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.ejb.AccessTimeout;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.inject.Inject;

import org.eclipse.egit.github.core.PullRequest;
import org.infinispan.api.BasicCache;
import org.jboss.logging.Logger;
import org.jboss.overview.model.OverviewData;
import org.jboss.pull.shared.Bug;
import org.jboss.pull.shared.BuildResult;
import org.jboss.pull.shared.PullHelper;
import org.jboss.pull.shared.spi.PullEvaluator;
import org.richfaces.application.push.MessageException;

/**
 * @author wangchao
 */

@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
public class SingletonAider {

    private final Logger LOGGER = Logger.getLogger(SingletonAider.class);
    private final String PULL_REQUEST_STATE = "open";
    private static final String CACHE_NAME = "cache";
    private PullHelper helper;
    private final long DELAY = 10; // 10 minutes delay before task is to be executed.
    private final long PERIOD = 60; // 60 minutes between successive task executions.

    @Inject
    private CacheContainerProvider provider;
    private BasicCache<Integer, OverviewData> cache;

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    public SingletonAider() {
    }

    @PostConstruct
    public void postConstruct() {
        // retrieve properties file defined in web.xml
        LOGGER.debug("pull.helper.property.file: " + System.getProperty("pull.helper.property.file"));
        try {
            helper = new PullHelper("pull.helper.property.file", "./processor.properties");
        } catch (Exception e) {
            e.printStackTrace(System.err);
            throw new RuntimeException(e);
//            System.exit(1);       ;-)
        }
        // create cache
        cache = provider.getCacheContainer().getCache(CACHE_NAME);

        // another thread initialize cache
        executorService.execute(new Runnable() {
            public void run() {
                initCache();
            }
        });

        // Scheduled task timer to update cache values
        scheduler.scheduleAtFixedRate(new TaskThread(), DELAY, PERIOD, TimeUnit.MINUTES);
    }

    @Lock(LockType.WRITE)
    public void initCache() {
        List<PullRequest> pullRequests = new ArrayList<PullRequest>();
        try {
            pullRequests = helper.getPullRequestService().getPullRequests(helper.getRepository(), PULL_REQUEST_STATE);
        } catch (IOException e) {
            LOGGER.error("Can not retrieve pull requests on repository : " + helper.getRepository());
            e.printStackTrace(System.err);
        }

        for (PullRequest pullRequest : pullRequests) {
            OverviewData pullRequestData = getOverviewData(pullRequest);
            cache.put(pullRequest.getNumber(), pullRequestData, -1, TimeUnit.SECONDS);

            try {
                DataTableScrollerBean.push();
            } catch (MessageException e) {
                e.printStackTrace(System.err);
            }
        }
    }

    public OverviewData getOverviewData(PullRequest pullRequest) {
        final BuildResult buildResult = helper.checkBuildResult(pullRequest);

        List<PullRequest> upStreamPullRequests = null;
        try {
            upStreamPullRequests = helper.getUpstreamPullRequest(pullRequest);
        } catch (IOException e) {
            System.err.printf("Cannot get an upstream pull requests of the pull request %d: %s.\n", pullRequest.getNumber(), e);
            e.printStackTrace(System.err);
        }

        final List<Bug> bugs = helper.getBug(pullRequest);

        final PullEvaluator.Result mergeable = helper.isMergeable(pullRequest);

        final List<String> overallState = mergeable.getDescription();

        return new OverviewData(pullRequest, buildResult, upStreamPullRequests, bugs, overallState, mergeable.isMergeable());
    }

    @Lock(LockType.WRITE)
    public void updateCache() {
        Set<Integer> keys = cache.keySet();

        List<PullRequest> pullRequests = new ArrayList<PullRequest>();
        try {
            pullRequests = helper.getPullRequestService().getPullRequests(helper.getRepository(), "open");
        } catch (IOException e) {
            LOGGER.info("Error to get pull requests on repository : " + helper.getRepository());
            e.printStackTrace(System.err);
        }

        Map<Integer, PullRequest> pullRequestsMap = new HashMap<Integer, PullRequest>();

        for (PullRequest pullRequest : pullRequests) {
            pullRequestsMap.put(pullRequest.getNumber(), pullRequest);
        }

        Set<Integer> ids = pullRequestsMap.keySet();

        // for all closed pull requests, remove from cache.
        for (Integer key : keys) {
            if (!ids.contains(key)) {
                cache.remove(key);
                try {
                    DataTableScrollerBean.push();
                } catch (MessageException e) {
                    e.printStackTrace(System.err);
                }
            }
        }

        // for all old pull request, update information
        keys = cache.keySet();
        for (Integer key : keys) {
            cache.replace(key, cache.get(key), getOverviewData(pullRequestsMap.get(key)));
            try {
                DataTableScrollerBean.push();
            } catch (MessageException e) {
                e.printStackTrace(System.err);
            }
        }

        // for all new pull requests, add into cache.
        for (Integer id : ids) {
            if (!keys.contains(id)) {
                OverviewData overviewData = getOverviewData(pullRequestsMap.get(id));
                cache.put(id, overviewData);
                try {
                    DataTableScrollerBean.push();
                } catch (MessageException e) {
                    e.printStackTrace(System.err);
                }
            }
        }
    }

    public PullHelper getHelper() {
        return helper;
    }

    @Lock(LockType.READ)
    @AccessTimeout(value = 4, unit = TimeUnit.SECONDS)
    public BasicCache<Integer, OverviewData> getCache() {
        return cache;
    }

    class TaskThread implements Runnable {
        @Override
        public void run() {
            updateCache();
        }
    }
}
