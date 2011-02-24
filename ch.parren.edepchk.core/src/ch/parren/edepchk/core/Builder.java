package ch.parren.edepchk.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import ch.parren.java.lang.New;
import ch.parren.jdepchk.check.Checker;
import ch.parren.jdepchk.check.Violation;
import ch.parren.jdepchk.check.ViolationListener;
import ch.parren.jdepchk.classes.AbstractClassFilesSet;
import ch.parren.jdepchk.classes.ClassFileReader;
import ch.parren.jdepchk.classes.ClassReader;
import ch.parren.jdepchk.rules.RuleSet;
import ch.parren.jdepchk.rules.parser.RuleSetLoader;

/**
 * Runs a JDepChk check on all the added/changed .class files reported to the
 * build.
 * <p>
 * Configuration is cached across runs and only refreshed if one of the
 * configuration file timestamps changes (config files or rules files). When the
 * configuration is changed, we run a full edepchk build instead of an
 * incremental one.
 * <p>
 * Configuration files (edepchk.conf, .edepchk) define which JDepChk rules files
 * to use for which output paths. They have the following syntax:
 * 
 * <pre>
 * a/binary/path/
 * another/binary/path/
 *     a/jdepchk/rules-file.jdep
 *     another/rules-file.jdep # a comment
 * # a comment
 * a/separate/binary/path/
 *     a/jdepchk/rules-file.jdep
 * </pre>
 */
public final class Builder extends IncrementalProjectBuilder {

	public static final String BUILDER_ID = "ch.parren.edepchk.core.edepchkBuilder";
	public static final String MARKER_TYPE = "ch.parren.edepchk.core.edepchkProblem";

	public static final String[] CONFIG_NAMES = { "edepchk.conf", ".edepchk" };

	private Config config = null;

	@Override protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
		try {
			if (null == config || kind == FULL_BUILD) {
				/*
				 * TODO: This does not properly handle the case where the first
				 * change you make is to one of the jdepchk rules files. We
				 * should do a full build in this case, but don't, because
				 * haven't cached the fingerprints of the rules files yet.
				 */
				config = new Config();
			} else if (!config.isUpToDate()) {
				config = new Config();
				kind = FULL_BUILD;
			}

			final Adapter adapter = new Adapter(config);

			Visitor visitor = new Visitor(adapter);
			if (kind == FULL_BUILD) {
				deleteMarkers(getProject());
				getProject().accept(visitor);
			} else {
				final IResourceDelta delta = getDelta(getProject());
				if (delta == null) {
					deleteMarkers(getProject());
					getProject().accept(visitor);
				} else {
					delta.accept(visitor);
				}
			}
			visitor = null;

			adapter.run();
			return null;

		} catch (CoreException ce) {
			throw ce;
		} catch (RuntimeException re) {
			throw re;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override protected void clean(IProgressMonitor monitor) throws CoreException {
		deleteMarkers(getProject());
	}

	private final class Visitor implements IResourceVisitor, IResourceDeltaVisitor {

		private final Adapter checker;

		private IPath topLevelPath = null;
		private Adapter.ClassPathSet currentScope = null;

		public Visitor(Adapter checker) {
			this.checker = checker;
		}

		@Override public boolean visit(IResourceDelta delta) throws CoreException {
			final IResource resource = delta.getResource();
			switch (delta.getKind()) {
			case IResourceDelta.ADDED:
			case IResourceDelta.CHANGED:
				return visit(resource);
			}
			return true;
		}

		@Override public boolean visit(IResource resource) throws CoreException {
			if (resource instanceof IFolder) {
				final IFolder folder = (IFolder) resource;
				final IPath fullPath = folder.getFullPath();
				if (null != topLevelPath && topLevelPath.isPrefixOf(fullPath))
					return true;
				topLevelPath = null;
				final String relPath = resource.getProjectRelativePath().toPortableString();
				currentScope = checker.setForPath(relPath);
				if (null != currentScope)
					topLevelPath = fullPath;
			} else if (resource instanceof IFile) {
				final IFile file = (IFile) resource;
				final String name = file.getName();
				if (name.endsWith(".class")) {
					if (null != currentScope)
						currentScope.addClassFile(file);
				} else if (JavaCore.isJavaLikeFileName(name))
					deleteMarkers(file);
			}
			return true;
		}
	}

	private final class Config {

		private final Collection<ClassPathSet> pathSets = New.linkedList();
		private final Map<File, FingerPrint> fingerPrints = New.hashMap();

		private int maxErrors = 500;

		public Config() throws Exception {
			for (String n : CONFIG_NAMES)
				tryToLoad(n);
		}

		public boolean isUpToDate() {
			for (FingerPrint fp : fingerPrints.values())
				if (!fp.isUpToDate())
					return false;
			return true;
		}

		private void fingerPrint(File file) {
			if (fingerPrints.containsKey(file))
				return;
			fingerPrints.put(file, new FingerPrint(file));
		}

		private void tryToLoad(String fileName) throws Exception {
			final IFile cfgFile = getProject().getFile(fileName);
			fingerPrint(cfgFile.getLocation().toFile());
			if (!cfgFile.exists())
				return;
			final BufferedReader cfgReader = new BufferedReader(new InputStreamReader(cfgFile.getContents()));
			try {
				String line;
				ClassPathSet scope = null;
				boolean pathStartsNewScope = true;
				while (null != (line = cfgReader.readLine())) {
					final int posOfComment = line.indexOf('#');
					if (posOfComment >= 0)
						line = line.substring(0, posOfComment);
					final String trimmed = line.trim();
					if (trimmed.isEmpty())
						continue;
					if (trimmed.startsWith("max-errors:")) {
						maxErrors = Integer.parseInt(trimmed.substring("max-errors:".length()).trim());
						continue;
					}
					final boolean isRulesFile = Character.isWhitespace(line.charAt(0));
					if (isRulesFile)
						if (null == scope)
							scope = newScope().addRulesFile(trimmed);
						else
							scope.addRulesFile(trimmed);
					else if (null == scope || pathStartsNewScope)
						// null check keeps compiler happy
						scope = newScope().addPath(trimmed);
					else
						scope.addPath(trimmed);
					pathStartsNewScope = isRulesFile;
				}
			} finally {
				cfgReader.close();
			}
		}

		private ClassPathSet newScope() {
			final ClassPathSet scope = new ClassPathSet();
			pathSets.add(scope);
			return scope;
		}

		public ClassPathSet scopeForPath(String relPath) {
			for (ClassPathSet scope : pathSets)
				if (scope.matches(relPath))
					return scope;
			return null;
		}

		private final class ClassPathSet {

			private final Collection<String> paths = New.linkedList();
			private final Collection<RuleSet> ruleSets = New.linkedList();

			public ClassPathSet addPath(String path) {
				if (path.endsWith("/"))
					path = path.substring(0, path.length() - 1);
				paths.add(path);
				return this;
			}

			public ClassPathSet addRulesFile(String filePath) throws Exception {
				final IPath path = Path.fromPortableString(filePath);
				final IPath fullPath = path.isAbsolute() ? path : getProject().getLocation().append(path);
				final File file = fullPath.toFile();
				fingerPrint(file);
				if (file.exists())
					ruleSets.add(RuleSetLoader.load(file));
				return this;
			}

			public boolean matches(String candidate) {
				for (String path : paths)
					if (candidate.equals(path))
						return true;
				return false;
			}
		}

		private final class FingerPrint {

			private final File file;
			private final long lastModified;

			public FingerPrint(File file) {
				this.file = file;
				this.lastModified = file.lastModified();
			}

			public boolean isUpToDate() {
				return file.lastModified() == this.lastModified;
			}
		}

	}

	private final class Adapter {

		private final Map<Config.ClassPathSet, ClassPathSet> pathSetsByConfig = New.hashMap();

		private final Config config;

		private int errorsFound = 0;

		public Adapter(Config config) {
			this.config = config;
		}

		public ClassPathSet setForPath(String relPath) {
			final Config.ClassPathSet cfg = config.scopeForPath(relPath);
			if (null == cfg)
				return null;
			final ClassPathSet cached = pathSetsByConfig.get(cfg);
			if (null != cached)
				return cached;
			final ClassPathSet created = new ClassPathSet(cfg);
			pathSetsByConfig.put(cfg, created);
			return created;
		}

		public void run() throws Exception {
			for (ClassPathSet pathSet : pathSetsByConfig.values())
				pathSet.run();
		}

		private final class ClassPathSet extends ViolationListener {

			private final Collection<IFile> classFiles = New.arrayList();
			private final Map<String, Collection<Violation>> violations = New.hashMap();
			private final String[] rootPaths;

			private final Config.ClassPathSet config;

			private ClassPathSet(Config.ClassPathSet config) {
				this.config = config;
				this.rootPaths = config.paths.toArray(new String[config.paths.size()]);
				/*
				 * Sort root paths by length (longest first) for finding the
				 * root path of a given path with early exit.
				 */
				for (int i = 0; i < rootPaths.length; i++)
					rootPaths[i] += '/';
				Arrays.sort(rootPaths, new Comparator<String>() {
					@Override public int compare(String o1, String o2) {
						final int l1 = o1.length();
						final int l2 = o2.length();
						return l1 < l2 ? +1 : l1 > l2 ? -1 : 0;
					}
				});
			}

			public void addClassFile(IFile file) {
				classFiles.add(file);
			}

			public void run() throws Exception {
				final Checker checker = new Checker(this, config.ruleSets);
				checker.check(new AbstractClassFilesSet<Object>() {
					private IFile currentFile;
					private String currentDir = "";
					private String currentRootPath;
					@Override public void accept(Visitor visitor) throws IOException {
						final Iterator<IFile> files = classFiles.iterator();
						accept(visitor, null, new Iterator<String>() {
							@Override public boolean hasNext() {
								return files.hasNext();
							}
							@Override public String next() {
								currentFile = files.next();
								final String path = currentFile.getProjectRelativePath().toPortableString();
								final int posOfName = path.lastIndexOf('/') + 1;
								final String newDir = (posOfName == 0) ? "" : path.substring(0, posOfName);
								if (!newDir.equals(currentDir)) {
									currentDir = newDir;
									currentRootPath = rootPathOf(newDir);
								}
								return path.substring(currentRootPath.length());
							}
							@Override public void remove() {
								throw new UnsupportedOperationException();
							}
						});
					}
					@Override protected void visit(Visitor visitor, String className, Object context)
							throws IOException {
						final ClassReader classFile = new ClassFileReader(className, currentFile.getLocation().toFile());
						try {
							visitor.visitClassFile(classFile);
						} finally {
							classFile.close();
						}
					}
				});
				addViolationMarkers();
			}

			private String rootPathOf(String path) {
				for (String root : rootPaths)
					if (path.startsWith(root))
						return root;
				throw new IllegalArgumentException(path);
			}

			@Override protected boolean report(Violation v) {
				if (++errorsFound > Adapter.this.config.maxErrors)
					return false;
				final String className = v.fromClassName;
				Collection<Violation> found = violations.get(className);
				if (null == found) {
					found = New.linkedList();
					violations.put(className, found);
				}
				found.add(v);
				return true;
			}

			private void addViolationMarkers() throws Exception {
				final IJavaProject javaProject = JavaCore.create(getProject());
				for (Map.Entry<String, Collection<Violation>> e : violations.entrySet()) {
					final String internalClassName = e.getKey();
					final String className = fromInternalName(internalClassName);
					final IType type = findType(javaProject, className);
					if (null == type)
						continue;
					final ICompilationUnit unit = type.getCompilationUnit();
					if (null == unit)
						continue;

					final IResource file = unit.getResource();
					for (final Violation v : e.getValue()) {
						final String toClassName = fromInternalName(v.toClassName);

						// Build message.
						final StringBuilder msg = new StringBuilder("Access to ").append(toClassName).append(" denied");
						String conjunction = " by";
						final String ruleMsg = v.scope.name();
						if (null != ruleMsg && !ruleMsg.isEmpty()) {
							msg.append(conjunction).append(" scope '").append(ruleMsg).append("'");
							conjunction = " in";
						}
						final String setName = v.ruleSet.name();
						if (null != setName && !setName.isEmpty() && !"<anonymous ruleset>".equals(setName))
							msg.append(conjunction).append(" ruleset '").append(setName).append("'");
						msg.append('.');

						// Try to use Java search.
						final boolean[] foundIt = { false };
						final IType toType = findType(javaProject, toClassName);
						if (null != toType) {
							final SearchPattern pat = SearchPattern.createPattern(toType,
									IJavaSearchConstants.REFERENCES);
							final IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
									new IJavaElement[] { unit }, IJavaSearchScope.SOURCES);
							final String fromClassName = fromInternalName(v.fromClassName);
							final SearchRequestor requestor = new SearchRequestor() {

								@Override public void acceptSearchMatch(SearchMatch match) throws CoreException {
									final Object elt = match.getElement();
									if (elt instanceof IImportDeclaration)
										return;
									final String eltClassName = fromInternalName(classNameOf(elt));
									if (null == eltClassName || eltClassName.equals(fromClassName)) {
										addMarker(file, msg.toString(), toMarkerSeverity(v), //
												match.getOffset(), match.getLength());
										foundIt[0] = true;
									}
								}

								private String classNameOf(Object element) {
									if (element instanceof IType)
										return ((IType) element).getFullyQualifiedName();
									if (element instanceof IMember)
										return ((IMember) element).getDeclaringType().getFullyQualifiedName();
									return null;
								}

							};
							final SearchEngine search = new SearchEngine();
							search.search(pat, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
									scope, requestor, null);
						}
						if (!foundIt[0]) {
							// No or unsuccessful Java search, so just annotate the class declaration.
							final ISourceRange range = type.getSourceRange();
							addMarker(file, msg.toString() + " (No direct source location found.)",
									toMarkerSeverity(v), //
									range.getOffset(), range.getLength());
						}
					}
				}
			}

			private String fromInternalName(String internalClassName) {
				if (null == internalClassName)
					return null;
				return internalClassName.replace('/', '.').replace('$', '.');
			}

			private IType findType(IJavaProject javaProject, String className) throws CoreException {
				final IType namedType = javaProject.findType(className.replace('$', '.'));
				if (null != namedType)
					return namedType;
				final int posOfInner = className.indexOf('$');
				if (posOfInner < 0)
					return null;
				return javaProject.findType(className.substring(0, posOfInner));
			}

			private int toMarkerSeverity(Violation v) {
				return IMarker.SEVERITY_ERROR;
//				if (RuleSeverity.ERROR == severity)
//					return IMarker.SEVERITY_ERROR;
//				else if (RuleSeverity.WARNING == severity)
//					return IMarker.SEVERITY_WARNING;
//				else
//					return IMarker.SEVERITY_INFO;
			}

		}

	}

	private void addMarker(IResource file, String message, int severity, int offs, int len) throws CoreException {
		final IMarker marker = file.createMarker(MARKER_TYPE);
		marker.setAttribute(IMarker.MESSAGE, message);
		marker.setAttribute(IMarker.SEVERITY, severity);
		marker.setAttribute(IMarker.CHAR_START, offs);
		marker.setAttribute(IMarker.CHAR_END, offs + len);
	}

	private void deleteMarkers(IResource res) throws CoreException {
		res.deleteMarkers(MARKER_TYPE, false, IResource.DEPTH_INFINITE);
	}

}
