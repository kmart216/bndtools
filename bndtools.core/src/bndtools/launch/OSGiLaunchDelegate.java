package bndtools.launch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;

import org.eclipse.core.internal.runtime.MetaDataKeeper;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.launching.JavaLaunchDelegate;

import aQute.bnd.build.Container;
import aQute.bnd.build.Container.TYPE;
import aQute.bnd.build.Project;
import aQute.bnd.build.ProjectLauncher;
import aQute.bnd.build.Workspace;
import aQute.lib.osgi.Builder;
import aQute.lib.osgi.Constants;
import aQute.lib.osgi.Processor;
import aQute.libg.header.OSGiHeader;
import bndtools.BndConstants;
import bndtools.Central;
import bndtools.Plugin;
import bndtools.builder.BndBuildJob;
import bndtools.builder.BndProjectNature;

public class OSGiLaunchDelegate extends JavaLaunchDelegate {

    private static final String LAUNCHER_BSN = "bndtools.launcher";
    private static final String LAUNCHER_MAIN_CLASS = LAUNCHER_BSN + ".Main";

    private static final String EMPTY = "";
    private static final String ANY_VERSION = "0"; //$NON-NLS-1$

    private ProjectLauncher bndLauncher = null;

    @Override
    public void launch(final ILaunchConfiguration configuration, String mode, final ILaunch launch, IProgressMonitor monitor) throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, 2);

        waitForBuilds(progress.newChild(1, SubMonitor.SUPPRESS_NONE));

        try {
            Project project = getBndProject(configuration);
            bndLauncher = project.getProjectLauncher();
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error obtaining OSGi project launcher.", e));
        }

        // TODO: call launcher.update() when necessary
        // registerLaunchPropertiesRegenerator(configuration, launch);

        super.launch(configuration, mode, launch, progress.newChild(1, SubMonitor.SUPPRESS_NONE));
    }

    protected void waitForBuilds(IProgressMonitor monitor) {
        SubMonitor progress = SubMonitor.convert(monitor, "Waiting for background Bnd builds to complete...", 1);
        try {
            Job.getJobManager().join(BndBuildJob.class, progress.newChild(1));
        } catch (OperationCanceledException e) {
            // Ignore
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    protected File getLaunchPropertiesFile(ILaunchConfiguration configuration) throws CoreException {
        IPath location = MetaDataKeeper.getMetaArea().getStateLocation(Plugin.PLUGIN_ID);
        File dir = location.toFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Unable to create state directory " + dir.getAbsolutePath(), null));
        }
        return new File(dir, configuration.getName() + ".properties");
    }

    /**
     * Generates the initial launch properties.
     * @param configuration
     * @throws CoreException
     */
    protected Properties generateLaunchProperties(ILaunchConfiguration configuration) throws CoreException {
        Project model = getBndProject(configuration);
        Properties outputProps = new Properties();

        // Set the default storage dir
        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_STORAGE_DIR, LaunchConstants.DEFAULT_LAUNCH_STORAGE_DIR_RUN);

        // Expand -runbundles
        Collection<String> runBundlePaths = calculateRunBundlePaths(model);
        outputProps.put(LaunchConstants.PROP_LAUNCH_RUNBUNDLES, Processor.join(runBundlePaths));

        // Copy misc properties
        copyConfigurationToLaunchFile(configuration, model, outputProps);

        return outputProps;
    }

    /**
     * Saves the launch properties into the file location indicated by the {@link #launchPropsFile} field.
     * @param props
     * @throws CoreException
     */
    protected void saveLaunchPropsFile(File launchFile, Properties props) throws CoreException {
        FileOutputStream out = null;
        try {
            System.out.println("SAVING: " + launchFile.getAbsolutePath());
            out = new FileOutputStream(launchFile, false);
            props.store(out, "Generated by Bndtools IDE");
        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error creating temporary launch properties file.", e));
        } finally {
            try {
                if(out != null) out.close();
            } catch (IOException e) {
            }
        }
    }

    protected boolean enableDebugOption(ILaunchConfiguration configuration) throws CoreException {
        Level logLevel = Level.parse(configuration.getAttribute(LaunchConstants.ATTR_LOGLEVEL, LaunchConstants.DEFAULT_LOGLEVEL));
        return logLevel.intValue() <= Level.FINE.intValue();
    }

    /**
     * Copies additional properties from both the launch configuration and the
     * project model itself into the launch properties file.
     *
     * @param configuration
     *            The launch configuration
     * @param model
     *            The project model
     * @param outputProps
     *            The output properties, which will be written to the launch
     *            properties file.
     * @throws CoreException
     */
    protected void copyConfigurationToLaunchFile(ILaunchConfiguration configuration, Project model, Properties outputProps) throws CoreException {
        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_DYNAMIC_BUNDLES,
                Boolean.toString(configuration.getAttribute(LaunchConstants.ATTR_DYNAMIC_BUNDLES, LaunchConstants.DEFAULT_DYNAMIC_BUNDLES)));

        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_LOGLEVEL,
                configuration.getAttribute(LaunchConstants.ATTR_LOGLEVEL, LaunchConstants.DEFAULT_LOGLEVEL));

        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_LOG_OUTPUT,
                configuration.getAttribute(LaunchConstants.ATTR_LOG_OUTPUT, LaunchConstants.DEFAULT_LOG_OUTPUT));

        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_CLEAN,
                Boolean.toString(configuration.getAttribute(LaunchConstants.ATTR_CLEAN, LaunchConstants.DEFAULT_CLEAN)));

        // Copy the -runproperties values
        String runPropsStr = model.getProperty(Constants.RUNPROPERTIES, "");
        Map<String, String> runProps = OSGiHeader.parseProperties(runPropsStr);
        for (Entry<String, String> entry : runProps.entrySet()) {
            String value = entry.getValue() != null
                ? entry.getValue()
                : "";
            outputProps.setProperty(entry.getKey(), value);
        }
    }

    /**
     * Calculates the full paths to the set of runtime bundles. Used to expand
     * the launch properties file.
     *
     * @param model
     *            The project model
     * @return A collection of absolute file paths
     * @throws CoreException
     */
    protected Collection<String> calculateRunBundlePaths(Project model) throws CoreException {
        Collection<String> runBundlePaths = new LinkedList<String>();
        try {
            // Calculate physical paths for -runbundles from the bnd/bndrun file
            synchronized (model) {
                Collection<Container> runbundles = model.getRunbundles();
                MultiStatus resolveErrors = new MultiStatus(Plugin.PLUGIN_ID, 0, "One or more run bundles could not be resolved.", null);
                for (Container container : runbundles) {
                    if (container.getType() == TYPE.ERROR) {
                        resolveErrors.add(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Could not resolve run bundle {0}, version {1}.",
                                container.getBundleSymbolicName(), container.getVersion()), null));
                    } else {
                        runBundlePaths.add(container.getFile().getAbsolutePath());
                    }
                }
                if (!resolveErrors.isOK()) {
                    throw new CoreException(resolveErrors);
                }
            }

            // Now we want to calculate the project's output builder.
            // First find the root project model (if this is a bndrun)
            File projectDir = model.getBase();
            Project rootModel = Workspace.getProject(projectDir);
            synchronized (rootModel ) {
                // Add the project's own output bundles
                Collection<? extends Builder> builders = rootModel.getSubBuilders();
                for (Builder builder : builders) {
                    File bundlefile = new File(model.getTarget(), builder.getBsn() + ".jar");
                    if(bundlefile.exists())
                        runBundlePaths.add(bundlefile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error finding run bundles.", e));
        }

        return runBundlePaths;
    }

    /**
     * Registers a resource listener with the project model file ({@code bnd.bnd}) to
     * update the project launcher properties file when the model changes. This
     * is used to dynamically change the set of runtime bundles. The resource
     * listener is automatically unregistered when the launched process
     * terminates.
     *
     * @param configuration
     * @param launch
     * @throws CoreException
     */
    protected void registerLaunchPropertiesRegenerator(final ILaunchConfiguration configuration, final ILaunch launch) throws CoreException {
        final Project model = getBndProject(configuration);

        final IPath propsPath = Central.toPath(model, model.getPropertiesFile());
        final IPath targetPath;
        final File launchPropsFile = getLaunchPropertiesFile(configuration);
        try {
            targetPath = Central.toPath(model, model.getTarget());
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error querying project output folder", e));
        }
        final IResourceChangeListener resourceListener = new IResourceChangeListener() {
            public void resourceChanged(IResourceChangeEvent event) {
                try {
                    boolean regenerate = false;

                    // Was the bnd.bnd file included in the delta?
                    IResourceDelta delta = event.getDelta().findMember(propsPath);
                    if (delta != null) {
                        if (delta.getKind() == IResourceDelta.CHANGED) {
                            regenerate = true;
                        } else if (delta.getKind() == IResourceDelta.REMOVED && launchPropsFile != null) {
                            launchPropsFile.delete();
                            return;
                        }
                    }
                    // Was the target path included in the delta? This might mean that sub-bundles have changed
                    regenerate = regenerate || event.getDelta().findMember(targetPath) != null;

                    if(regenerate) {
                        Properties launchProps = generateLaunchProperties(configuration);
                        saveLaunchPropsFile(launchPropsFile, launchProps);
                    }
                } catch (Exception e) {
                    IStatus status = new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error updating launch properties file.", e);
                    Plugin.log(status);
                }
            }
        };
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener);

        // Register a listener for termination of the launched process
        Runnable onTerminate = new Runnable() {
            public void run() {
                System.out.println("Processes terminated.");
                ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
            }
        };
        DebugPlugin.getDefault().addDebugEventListener(new TerminationListener(launch, onTerminate));
    }

    @Override
    public String getMainTypeName(ILaunchConfiguration configuration) throws CoreException {
        assertBndLauncher();
        return bndLauncher.getMainTypeName();
    }

    private void assertBndLauncher() throws CoreException {
        if (bndLauncher == null)
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Bnd launcher was not initialised.", null));
    }

    @Override
    public String getProgramArguments(ILaunchConfiguration configuration) throws CoreException {
        assertBndLauncher();

        StringBuilder builder = new StringBuilder();

        Collection<String> args = bndLauncher.getArguments();
        for (Iterator<String> iter = args.iterator(); iter.hasNext();) {
            builder.append(iter.next());
            if (iter.hasNext()) builder.append(" ");
        }

        return builder.toString();
    }

    protected Project getBndProject(ILaunchConfiguration configuration) throws CoreException {
        Project result;

        String target = configuration.getAttribute(LaunchConstants.ATTR_LAUNCH_TARGET, (String) null);
        if(target == null) {
            // For compatibility with launches created in previous versions
            target = getJavaProjectName(configuration);
        }
        if(target == null) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Bnd launch target was not specified", null));
        }

        IResource targetResource = ResourcesPlugin.getWorkspace().getRoot().findMember(target);
        if(targetResource == null)
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Bnd launch target \"{0}\" does not exist.", target), null));

        IProject project = targetResource.getProject();
        File projectDir = project.getLocation().toFile();
        if(targetResource.getType() == IResource.FILE) {
            if(!targetResource.getName().endsWith(LaunchConstants.EXT_BNDRUN))
                throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Bnd launch target file \"{0}\" is not a .bndrun file.", target), null));

            // Get the synthetic "run" project (based on a .bndrun file)
            File runFile = targetResource.getLocation().toFile();
            try {
                result = new Project(Central.getWorkspace(), projectDir, runFile);
            } catch (Exception e) {
                throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Failed to create synthetic project for run file {0} in project {1}.", targetResource.getProjectRelativePath().toString(), project.getName()), e));
            }
        } else if(targetResource.getType() == IResource.PROJECT) {
            // Use the main project (i.e. bnd.bnd)
            if(!project.hasNature(BndProjectNature.NATURE_ID))
                throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("The configured run project \"{0}\"is not a Bnd project.", project.getName()), null));
            try {
                result = Workspace.getProject(projectDir);
            } catch (Exception e) {
                throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Failed to retrieve Bnd project model for project \"{0}\".", project.getName()), null));
            }
        } else {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("The specified launch target \"{0}\" is not recognised as a Bnd project or .bndrun file.", targetResource.getFullPath().toString()), null));
        }

        return result;
    }

    @Override
    public String[] getClasspath(ILaunchConfiguration configuration) throws CoreException {
        assertBndLauncher();
        Collection<String> paths = bndLauncher.getClasspath();
        return paths.toArray(new String[paths.size()]);
    }

    @Override
    public String getVMArguments(ILaunchConfiguration configuration) throws CoreException {
        assertBndLauncher();

        StringBuilder builder = new StringBuilder();
        Collection<String> runVM = bndLauncher.getRunVM();
        for (Iterator<String> iter = runVM.iterator(); iter.hasNext();) {
            builder.append(iter.next());
            if (iter.hasNext()) builder.append(" ");
        }
        String args = builder.toString();

        // Following code copied from AbstractJavaLaunchConfigurationDelegate
        int libraryPath = args.indexOf("-Djava.library.path"); //$NON-NLS-1$
        if (libraryPath < 0) {
            // if a library path is already specified, do not override
            String[] javaLibraryPath = getJavaLibraryPath(configuration);
            if (javaLibraryPath != null && javaLibraryPath.length > 0) {
                StringBuffer path = new StringBuffer(args);
                path.append(" -Djava.library.path="); //$NON-NLS-1$
                path.append("\""); //$NON-NLS-1$
                for (int i = 0; i < javaLibraryPath.length; i++) {
                    if (i > 0) {
                        path.append(File.pathSeparatorChar);
                    }
                    path.append(javaLibraryPath[i]);
                }
                path.append("\""); //$NON-NLS-1$
                args = path.toString();
            }
        }
        return args;
    }

    @Override
    public File verifyWorkingDirectory(ILaunchConfiguration configuration) throws CoreException {
        try {
            Project project = getBndProject(configuration);
            return (project != null) ? project.getBase() : null;
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error getting working directory for Bnd project.", e));
        }
    }

    protected File findFramework(Project model) throws CoreException {
        String frameworkSpec = model.getProperty(BndConstants.RUNFRAMEWORK, EMPTY);
        if(frameworkSpec == null || frameworkSpec.length() == 0) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("No OSGi framework was specified in {0}.", model.getPropertiesFile().getAbsolutePath()), null));
        }
        Map<String, Map<String, String>> fwkHeader = OSGiHeader.parseHeader(frameworkSpec);
        if(fwkHeader.size() != 1) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Invalid format for OSGi framework specification.", null));
        }
        Entry<String, Map<String, String>> fwkHeaderEntry = fwkHeader.entrySet().iterator().next();
        String fwkBSN = fwkHeaderEntry.getKey();

        String fwkVersion = fwkHeaderEntry.getValue().get(Constants.VERSION_ATTRIBUTE);
        if(fwkVersion == null)
            fwkVersion = ANY_VERSION;

        File fwkBundle = findBundle(model, fwkBSN, fwkVersion);
        if (fwkBundle == null) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Could not find framework {0}, version {1}.", fwkBSN, fwkVersion), null));
        }
        return fwkBundle;
    }

    protected File findBundle(Project project, String bsn, String version) throws CoreException {
        try {
            Container snapshotContainer = project.getBundle(bsn, "snapshot", Constants.STRATEGY_HIGHEST, null);
            if (snapshotContainer != null && snapshotContainer.getType() != TYPE.ERROR) {
                return snapshotContainer.getFile();
            }

            Container repoContainer = project.getBundle(bsn, version, Constants.STRATEGY_HIGHEST, null);
            if (repoContainer != null && repoContainer.getType() != TYPE.ERROR) {
                return repoContainer.getFile();
            }
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format(
                    "An error occurred while searching the workspace or repositories for bundle {0}.", bsn), e));
        }
        return null;
    }
}