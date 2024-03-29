package ch.parren.edepchk.core;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

/** Adds/removes the {@link Builder} when added/removed. */
public class Nature implements IProjectNature {

	public static final String NATURE_ID = "ch.parren.edepchk.core.edepchkNature";

	private IProject project;

	@Override public void configure() throws CoreException {
		final IProjectDescription desc = project.getDescription();
		final ICommand[] commands = desc.getBuildSpec();
		for (int i = 0; i < commands.length; ++i)
			if (commands[i].getBuilderName().equals(Builder.BUILDER_ID))
				return;
		final ICommand[] newCommands = new ICommand[commands.length + 1];
		System.arraycopy(commands, 0, newCommands, 0, commands.length);
		final ICommand command = desc.newCommand();
		command.setBuilderName(Builder.BUILDER_ID);
		newCommands[newCommands.length - 1] = command;
		desc.setBuildSpec(newCommands);
		project.setDescription(desc, null);
	}

	@Override public void deconfigure() throws CoreException {
		project.deleteMarkers(Builder.MARKER_TYPE, false, IResource.DEPTH_INFINITE);
		final IProjectDescription description = project.getDescription();
		final ICommand[] commands = description.getBuildSpec();
		for (int i = 0; i < commands.length; ++i) {
			if (commands[i].getBuilderName().equals(Builder.BUILDER_ID)) {
				final ICommand[] newCommands = new ICommand[commands.length - 1];
				System.arraycopy(commands, 0, newCommands, 0, i);
				System.arraycopy(commands, i + 1, newCommands, i, commands.length - i - 1);
				description.setBuildSpec(newCommands);
				project.setDescription(description, null);
				return;
			}
		}
	}

	@Override public IProject getProject() {
		return project;
	}

	@Override public void setProject(IProject project) {
		this.project = project;
	}

}
