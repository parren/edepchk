package ch.parren.edepchk.core;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import ch.parren.edepchk.core.builder.Builder;
import ch.parren.edepchk.core.builder.Nature;

/**
 * Auto-configures projects to include the Macker nature exactly if they have a
 * macker config file. Config files are called either "emacker.conf" or
 * ".emacker". See {@link Builder} for details on the configuration
 * syntax.
 */
public class Activator implements BundleActivator {

	private static final String CONFIG_NAMES;
	static {
		final StringBuilder b = new StringBuilder(":");
		for (String fileName : Builder.CONFIG_NAMES)
			b.append(fileName).append(":");
		CONFIG_NAMES = b.toString();
	}

	@Override public void start(BundleContext bundleContext) throws Exception {
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		workspace.addResourceChangeListener(resourceListener, //
				IResourceChangeEvent.POST_CHANGE | IResourceChangeEvent.PRE_BUILD);
		configureProjects(workspace);
	}

	@Override public void stop(BundleContext bundleContext) throws Exception {
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		workspace.removeResourceChangeListener(resourceListener);
	}

	private IResourceChangeListener resourceListener = new IResourceChangeListener() {

		private final Set<IProject> pending = new HashSet<IProject>();

		@Override public void resourceChanged(IResourceChangeEvent event) {
			try {
				switch (event.getType()) {
				case IResourceChangeEvent.POST_CHANGE:
					event.getDelta().accept(visitor);
					break;
				case IResourceChangeEvent.PRE_BUILD:
					for (IProject p : pending)
						configureProject(p);
					pending.clear();
					break;
				}
			} catch (CoreException e) {
				throw new RuntimeException(e);
			}
		}

		private final IResourceDeltaVisitor visitor = new IResourceDeltaVisitor() {
			@Override public boolean visit(IResourceDelta delta) throws CoreException {
				switch (delta.getKind()) {
				case IResourceDelta.ADDED:
				case IResourceDelta.REMOVED:
					final IResource res = delta.getResource();
					if (res instanceof IFile) {
						final IPath path = res.getProjectRelativePath();
						if (path.segmentCount() == 1 && CONFIG_NAMES.contains(path.segment(0)))
							pending.add(res.getProject());
					}
					break;
				}
				return true;
			}
		};

	};

	private void configureProjects(IWorkspace workspace) throws CoreException {
		for (IProject project : workspace.getRoot().getProjects())
			configureProject(project);
	}

	private void configureProject(IProject project) throws CoreException {
		if (!project.isOpen())
			return;
		final IProjectDescription desc = project.getDescription();
		final boolean hasNature = desc.hasNature(Nature.NATURE_ID);
		final boolean hasConfig = hasConfig(project);
		if (hasNature == hasConfig)
			return;
		if (hasNature)
			desc.setNatureIds(removeNature(desc.getNatureIds()));
		else
			desc.setNatureIds(addNature(desc.getNatureIds()));
		project.setDescription(desc, null);
	}

	private boolean hasConfig(IProject project) {
		for (String fileName : Builder.CONFIG_NAMES)
			if (project.getFile(fileName).exists())
				return true;
		return false;
	}

	private String[] addNature(String[] natureIds) {
		final List<String> ids = idsToList(natureIds);
		ids.add(Nature.NATURE_ID);
		return ids.toArray(new String[ids.size()]);
	}

	private String[] removeNature(String[] natureIds) {
		final List<String> ids = idsToList(natureIds);
		ids.remove(Nature.NATURE_ID);
		return ids.toArray(new String[ids.size()]);
	}

	private List<String> idsToList(String[] natureIds) {
		final List<String> ids = new LinkedList<String>();
		for (String id : natureIds)
			ids.add(id);
		return ids;
	}

}
