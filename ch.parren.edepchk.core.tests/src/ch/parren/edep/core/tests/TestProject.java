/* Adapted from the sample in the book "Contributing to Eclipse" by Gamma and Beck. */
package ch.parren.edep.core.tests;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.eclipse.jdt.launching.JavaRuntime;

public class TestProject {
	public IProject project;
	public IJavaProject javaProject;
	private IPackageFragmentRoot sourceFolder;

	public TestProject() throws CoreException {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		project = root.getProject("Project-1");
		project.create(null);
		project.open(null);

		javaProject = JavaCore.create(project);
		IFolder binFolder = createBinFolder();
		setJavaNature();
		javaProject.setRawClasspath(new IClasspathEntry[0], null);
		createOutputFolder(binFolder);
		addSystemLibraries();
	}

	public IProject getProject() {
		return project;
	}

	public IJavaProject getJavaProject() {
		return javaProject;
	}

	public void addJar(File jar) throws JavaModelException, IOException {
		Path path = new Path(jar.getCanonicalPath());
		IClasspathEntry entry = JavaCore.newLibraryEntry(path, null, null);
		IClasspathEntry[] oldEntries = javaProject.getRawClasspath();
		IClasspathEntry[] newEntries = new IClasspathEntry[oldEntries.length + 1];
		System.arraycopy(oldEntries, 0, newEntries, 0, oldEntries.length);
		newEntries[oldEntries.length] = entry;
		javaProject.setRawClasspath(newEntries, null);
	}

	public IPackageFragment createPackage(String name) throws CoreException {
		if (sourceFolder == null)
			sourceFolder = createSourceFolder();
		return sourceFolder.createPackageFragment(name, false, null);
	}

	public IFile createPackageInfo(IPackageFragment pack, String annotations, String imports) throws CoreException {
		StringBuffer buf = new StringBuffer();
		buf.append(annotations);
		buf.append("\npackage " + pack.getElementName() + ";\n");
		buf.append("\n");
		buf.append(imports);

		final IFile file = ((IFolder) pack.getCorrespondingResource()).getFile("package-info.java");
		if (file.exists())
			file.setContents(new ByteArrayInputStream(buf.toString().getBytes()), 0, null);
		else
			file.create(new ByteArrayInputStream(buf.toString().getBytes()), true, null);
		return file;
	}

	public IType createType(IPackageFragment pack, String cuName, String source) throws JavaModelException {
		StringBuffer buf = new StringBuffer();
		buf.append("package " + pack.getElementName() + ";\n");
		buf.append("\n");
		buf.append(source);
		ICompilationUnit cu = pack.createCompilationUnit(cuName, buf.toString(), false, null);
		return cu.getTypes()[0];
	}

	public void dispose() throws CoreException {
		waitForIndexer();
		project.delete(true, true, null);
	}

	private IFolder createBinFolder() throws CoreException {
		IFolder binFolder = project.getFolder("bin");
		binFolder.create(false, true, null);
		return binFolder;
	}

	private void setJavaNature() throws CoreException {
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID });
		project.setDescription(description, null);
	}

	private void createOutputFolder(IFolder binFolder) throws JavaModelException {
		IPath outputLocation = binFolder.getFullPath();
		javaProject.setOutputLocation(outputLocation, null);
	}

	private IPackageFragmentRoot createSourceFolder() throws CoreException {
		IFolder folder = project.getFolder("src");
		folder.create(false, true, null);
		IPackageFragmentRoot root = javaProject.getPackageFragmentRoot(folder);

		IClasspathEntry[] oldEntries = javaProject.getRawClasspath();
		IClasspathEntry[] newEntries = new IClasspathEntry[oldEntries.length + 1];
		System.arraycopy(oldEntries, 0, newEntries, 0, oldEntries.length);
		newEntries[oldEntries.length] = JavaCore.newSourceEntry(root.getPath());
		javaProject.setRawClasspath(newEntries, null);
		return root;
	}

	private void addSystemLibraries() throws JavaModelException {
		IClasspathEntry[] oldEntries = javaProject.getRawClasspath();
		IClasspathEntry[] newEntries = new IClasspathEntry[oldEntries.length + 1];
		System.arraycopy(oldEntries, 0, newEntries, 0, oldEntries.length);
		newEntries[oldEntries.length] = JavaRuntime.getDefaultJREContainerEntry();
		javaProject.setRawClasspath(newEntries, null);
	}

	private void waitForIndexer() throws JavaModelException {
		new SearchEngine().searchAllTypeNames(null, 0, null, 0, IJavaSearchConstants.ALL_OCCURRENCES,
				SearchEngine.createJavaSearchScope(new IJavaElement[0]), new TypeNameMatchRequestor() {
					@Override public void acceptTypeNameMatch(TypeNameMatch arg0) {}
				}, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, null);
	}

}
