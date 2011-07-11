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
import java.util.Set;

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
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
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
import ch.parren.jdepchk.classes.ClassFile;
import ch.parren.jdepchk.classes.ClassSets;
import ch.parren.jdepchk.config.OptionsParser;
import ch.parren.jdepchk.config.OptionsParser.ErrorReport;
import ch.parren.jdepchk.rules.RuleSet;
import ch.parren.jdepchk.rules.parser.FileParseException;
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
	public static final String RULE_MARKER_TYPE = "ch.parren.edepchk.core.edepchkParseError";

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
				config = parseConfig();
			} else if (!config.isUpToDate()) {
				config = parseConfig();
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
		} catch (ErrorReport e) {
			throw new RuntimeException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected Config parseConfig() throws Exception, ErrorReport {
		getProject().deleteMarkers(RULE_MARKER_TYPE, false, IResource.DEPTH_INFINITE);
		return new Config();
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

		public Config() throws Exception, ErrorReport {
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

		private void tryToLoad(String fileName) throws Exception, ErrorReport {
			final IFile cfgFile = getProject().getFile(fileName);
			fingerPrint(cfgFile.getLocation().toFile());
			if (!cfgFile.exists())
				return;

			final OptionsParser parser = new OptionsParser() {

				private ClassPathSet scope;

				@Override protected void visitScopeStart(String name) throws IOException, ErrorReport {
					scope = newScope();
				}

				@Override protected void visitClasses(String spec) throws IOException, ErrorReport {
					scope.addPath(spec);
				}

				@Override protected void visitClassSets(ClassSets classSets) throws IOException, ErrorReport {}

				@Override protected void visitCheckClasses(boolean active) throws IOException, ErrorReport {}

				@Override protected void visitRuleSetStart(String name) throws IOException, ErrorReport {}

				@Override protected void visitRuleSpec(String spec) throws IOException, ErrorReport {
					scope.addRulesFile(spec);
				}

				@Override protected void visitRuleSetEnd() throws IOException, ErrorReport {}

				@Override protected void visitExtractAnnotations(boolean active) throws IOException, ErrorReport {}

				@Override protected void visitLocalRulesDir(File dir) throws IOException, ErrorReport {}

				@Override protected void visitGlobalRulesDir(File dir) throws IOException, ErrorReport {}

				@Override protected void visitScopeEnd() throws IOException, ErrorReport {}

				@Override protected void visitArg(String arg, Iterator<String> more, boolean flagUnknown)
						throws IOException, ErrorReport {
					if ("--max-errors".equals(arg))
						maxErrors = Integer.parseInt(more.next());
					else
						super.visitArg(arg, more, flagUnknown);
				}

			};

			final BufferedReader cfgReader = new BufferedReader(new InputStreamReader(cfgFile.getContents()));
			try {
				parser.parseOptionsFile(cfgReader);
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

			public ClassPathSet addRulesFile(String filePath) throws IOException {
				final IPath path = Path.fromPortableString(filePath);
				final IPath fullPath = path.isAbsolute() ? path : getProject().getLocation().append(path);
				final File file = fullPath.toFile();
				fingerPrint(file);
				if (file.exists())
					try {
						final RuleSet ruleSet = RuleSetLoader.load(file);
						ruleSets.add(ruleSet);
					} catch (FileParseException pe) {
						final IFile res = getProject().getFile(path);
						IMarker marker;
						try {
							marker = res.createMarker(RULE_MARKER_TYPE);
							marker.setAttribute(IMarker.MESSAGE, pe.cause.getMessage());
							marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
							marker.setAttribute(IMarker.CHAR_START, pe.cause.startOffs);
							marker.setAttribute(IMarker.CHAR_END, pe.cause.endOffs + 1);
						} catch (CoreException e) {
							throw new RuntimeException(e);
						}
					}
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
				final AbstractClassFilesSet<Object> classFilesSet = new AbstractClassFilesSet<Object>() {
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
						acceptClassBytes(visitor, new ClassFile(className, currentFile.getLocation().toFile()));
					}
				};
				classFilesSet.accept(checker.newClassSetVisitor());
				addViolationMarkers();
			}

			private String rootPathOf(String path) {
				for (String root : rootPaths)
					if (path.startsWith(root))
						return root;
				throw new IllegalArgumentException(path);
			}

			@Override public boolean report(Violation v) {
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

					// Avoid reporting references to the same element twice (classes in particular).
					final Set<IJavaElement> seen = New.hashSet();

					// Add untargeted markers only if no targeted marker was found for a given target type.
					String lastToClassName = null;
					String lastMessage = null;
					boolean hadMatch = true;

					final IResource file = unit.getResource();
					for (final Violation v : e.getValue()) {
						final String toClassName = fromInternalName(v.toClassName);
						final String msg = buildMessage(v, toClassName);

						if (!toClassName.equals(lastToClassName)) {
							if (!hadMatch)
								addUntargetedMarker(type, file, lastMessage);
							lastToClassName = toClassName;
							lastMessage = msg;
							hadMatch = false;
						}

						// Try to use Java search.
						final boolean[] foundIt = { false };
						final IType toType = findType(javaProject, toClassName);
						if (null != toType) {
							final IJavaElement toElt = findElement(toType, v);
							if (!seen.add(toElt))
								continue;

							final SearchPattern pat = SearchPattern.createPattern(toElt,
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
										addMarker(file, msg, IMarker.SEVERITY_ERROR, //
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
						hadMatch |= foundIt[0];
					}

					if (null != lastToClassName && !hadMatch)
						addUntargetedMarker(type, file, lastMessage);
				}
			}

			/**
			 * No or unsuccessful Java search, so just annotate the class
			 * declaration.
			 */
			protected void addUntargetedMarker(IType type, IResource file, String msg) throws JavaModelException,
					CoreException {
				final ISourceRange range = type.getNameRange();
				addMarker(file, msg + " (No direct source location found.)", IMarker.SEVERITY_ERROR, //
						range.getOffset(), range.getLength());
			}

			private String fromInternalName(String internalClassName) {
				if (null == internalClassName)
					return null;
				return internalClassName.replace('/', '.').replace('$', '.');
			}

			protected String buildMessage(final Violation v, String toClassName) {
				final StringBuilder sb = new StringBuilder("Access to ").append(toClassName);
				if (null != v.toElementName)
					sb.append(".").append(v.toElementName);
				sb.append(" denied");
				String conjunction = " by";
				final String ruleMsg = v.scope.name();
				if (null != ruleMsg && !ruleMsg.isEmpty()) {
					sb.append(conjunction).append(" scope '").append(ruleMsg).append("'");
					conjunction = " in";
				}
				final String setName = v.ruleSet.name();
				if (null != setName && !setName.isEmpty() && !"<anonymous ruleset>".equals(setName))
					sb.append(conjunction).append(" ruleset '").append(setName).append("'");
				sb.append('.');
				return sb.toString();
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

			protected IJavaElement findElement(final IType inType, final Violation v) throws JavaModelException {
				if (null != v.toElementName) {
					final IJavaElement[] children = inType.getChildren();
					// TODO There may be a better way than this.
					for (IJavaElement elt : children) {
						if (elt instanceof IMember) {
							final IMember mem = (IMember) elt;
							if (v.toElementName.equals(mem.getElementName())) {
								if (mem instanceof IMethod) {
									final IMethod mtd = (IMethod) mem;
									final String sig = mtd.getSignature();
									if (v.toElementDesc.equals(sig))
										return mtd;
								} else if (mem instanceof IField) {
									final IField fld = (IField) mem;
									if (v.toElementDesc.equals(fld.getTypeSignature()))
										return fld;
								}
							}
						}
					}
				}
				return inType;
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
