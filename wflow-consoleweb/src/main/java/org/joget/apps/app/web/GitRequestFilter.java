package org.joget.apps.app.web;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.merge.MergeStrategy;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppDevUtil;
import org.joget.apps.app.dao.GitCommitHelper;
import static org.joget.apps.app.service.AppDevUtil.ATTRIBUTE_GIT_COMMIT_REQUEST;
import static org.joget.apps.app.service.AppDevUtil.getGitBranchName;
import org.joget.commons.util.LogUtil;
import org.joget.workflow.util.WorkflowUtil;
import static org.joget.apps.app.service.AppDevUtil.getAppDevProperties;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.PluginThread;
import org.joget.workflow.model.service.WorkflowUserManager;

@WebFilter(urlPatterns="/web/*", asyncSupported=true)
public class GitRequestFilter implements Filter {

    /**
     * <p>Synchronous commit prevents the git commits from the Thread spawned by this filter to be interfered with.
     * <p>Other threads can enable synchronous commit by setting this request attribute value to a boolean value {@code true}. Defaults to {@code false} if not set.
     */
    public static final String REQUEST_ATTRIBUTE_ENABLE_SYNCHRONOUS_COMMIT = "REQUEST_ATTRIBUTE_ENABLE_SYNCHRONOUS_COMMIT";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        chain.doFilter(request, response);

        if (!AppDevUtil.isGitDisabled()) {
            boolean enableSynchronousCommit = false;
            Object o = request.getAttribute(REQUEST_ATTRIBUTE_ENABLE_SYNCHRONOUS_COMMIT);
            if (o instanceof Boolean) {
                enableSynchronousCommit = (Boolean) o;
            } else if (o instanceof String) {
                enableSynchronousCommit = Boolean.parseBoolean((String) o);
            }

            CountDownLatch latch = null;
            if (enableSynchronousCommit) {
                latch = new CountDownLatch(1);
            }

            // commit
            final Map<String, GitCommitHelper> gitCommitMap = (Map<String, GitCommitHelper>)WorkflowUtil.getHttpServletRequest().getAttribute(ATTRIBUTE_GIT_COMMIT_REQUEST);
            WorkflowUserManager wum = (WorkflowUserManager)AppUtil.getApplicationContext().getBean("workflowUserManager");
            final String currentUser = wum.getCurrentUsername();
            if (gitCommitMap != null && !gitCommitMap.isEmpty()) {
                final boolean finalEnableSynchronousCommit = enableSynchronousCommit;
                final CountDownLatch finalLatch = latch;
                Thread gitThread = new PluginThread(new Runnable() {
                    
                    @Override
                    public void run() {
                        WorkflowUserManager wum = (WorkflowUserManager)AppUtil.getApplicationContext().getBean("workflowUserManager");
                        wum.setCurrentThreadUser(currentUser);
                        
                        Collection<AppDefinition> pushAppDefs = new LinkedHashSet<>();

                        try {
                            for (String appId : gitCommitMap.keySet()) {
                                GitCommitHelper gitCommitHelper = gitCommitMap.get(appId);

                                if (gitCommitHelper != null) {
                                    try {
                                        Git git = gitCommitHelper.getGit();
                                        AppDefinition appDef = gitCommitHelper.getAppDefinition();
                                        AppUtil.setCurrentAppDefinition(appDef); //to make sure log viewer able to capture the log to app log

                                        // perform commit
                                        String commitMessage = gitCommitHelper.getCommitMessage();
                                        if (gitCommitHelper.hasChanges() && commitMessage != null && !commitMessage.trim().isEmpty()) {
                                            // sync plugins
                                            if (gitCommitHelper.isSyncPlugins()) {
                                                AppDevUtil.syncAppPlugins(appDef);
                                            }

                                            // sync resources
                                            if (gitCommitHelper.isSyncResources()) {
                                                AppDevUtil.syncAppResources(appDef);
                                            }

                                            AppDevUtil.gitPullAndCommit(appDef, git, gitCommitHelper.getWorkingDir(), commitMessage);
                                            pushAppDefs.add(appDef);
                                        }
                                    } catch (Exception ex) {
                                        LogUtil.error(getClass().getName(), ex, ex.getMessage());
                                    } finally {
                                        try {
                                            gitCommitHelper.clean();
                                        } catch (Exception e) {
                                            LogUtil.debug(GitRequestFilter.class.getName(), appId + " - " + e.getMessage());
                                        }
                                        AppUtil.resetAppDefinition();
                                    }
                                }
                            }
                        } finally {
                            // release latch once commit is completed
                            if (finalEnableSynchronousCommit) {
                                finalLatch.countDown();
                            }
                        }

                        // push
                        if (pushAppDefs != null && !pushAppDefs.isEmpty()) {
                            for (AppDefinition appDef: pushAppDefs) {
                                String baseDir = AppDevUtil.getAppDevBaseDirectory();
                                String projectDirName = AppDevUtil.getAppGitDirectory(appDef);
                                File projectDir = AppDevUtil.dirSetup(baseDir, projectDirName);
                                String gitBranch = getGitBranchName(appDef);
                                Properties gitProperties = getAppDevProperties(appDef);
                                String gitUri = gitProperties.getProperty(AppDevUtil.PROPERTY_GIT_URI);
                                String gitUsername = gitProperties.getProperty(AppDevUtil.PROPERTY_GIT_USERNAME);
                                String gitPassword = gitProperties.getProperty(AppDevUtil.PROPERTY_GIT_PASSWORD);
                                try {
                                    AppUtil.setCurrentAppDefinition(appDef);
                                    
                                    Git git = AppDevUtil.gitInit(projectDir);
                                    if (gitUri != null && !gitUri.trim().isEmpty()) {

                                        try {
                                            AppDevUtil.gitAddRemote(git, gitUri);
                                        } catch(RefNotAdvertisedException re) {
                                            // ignore
                                        }
                                        AppDevUtil.gitPullAndPush(projectDir, git, gitBranch, gitUri, gitUsername, gitPassword, MergeStrategy.RECURSIVE, appDef);
                                    }
                                } catch (Exception ex) {
                                    LogUtil.error(getClass().getName(), ex, ex.getMessage());
                                } finally {
                                    AppUtil.resetAppDefinition();
                                }
                            }
                        }
                    }
                });
                gitThread.setDaemon(false);
                gitThread.start();

                // await for synchronous commit to complete before continuing
                if (enableSynchronousCommit) {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        LogUtil.error(getClass().getName(), e, "Failed to await for synchronous commit lock");
                    }
                }
            }
        }
    }

    @Override
    public void destroy() {
    }
    
}
