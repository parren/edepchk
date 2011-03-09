package ch.parren.edep.core.tests;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.FileWriter;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IPackageFragment;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ch.parren.edepchk.core.Builder;
import ch.parren.edepchk.core.Nature;

public class SimpleTest {

	private TestProject testProject;
	private IProject project;

	@Before public void setUp() throws Exception {
		testProject = new TestProject();
		project = testProject.getProject();
	}

	@After public void tearDown() throws Exception {
		project = null;
		testProject.dispose();
		testProject = null;
	}

	@Test public void activation() throws Exception {
		final IFile rules = project.getFile("rules.jdep");
		rules.create(new ByteArrayInputStream("lib $default contains java.**".getBytes()), true, null);

		// activate through Eclipse
		final IFile config = project.getFile("edepchk.conf");
		config.create(new ByteArrayInputStream("bin/\n  rules.jdep\n".getBytes()), true, null);

		assertNull(project.getNature(Nature.NATURE_ID));
		project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
		assertNotNull(project.getNature(Nature.NATURE_ID));

		// deactivate through Eclipse
		final IPath configLoc = config.getLocation();
		config.move(config.getFullPath().addFileExtension("deact"), true, null);
		assertNotNull(project.getNature(Nature.NATURE_ID));
		project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
		assertNull(project.getNature(Nature.NATURE_ID));

		// reactivate through file system
		configLoc.addFileExtension("deact").toFile().renameTo(configLoc.toFile());
		config.refreshLocal(IResource.DEPTH_ZERO, null);
		assertNull(project.getNature(Nature.NATURE_ID));
		project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
		assertNotNull(project.getNature(Nature.NATURE_ID));

		// deactivate through file system
		configLoc.toFile().delete();
		config.refreshLocal(IResource.DEPTH_ZERO, null);
		assertNotNull(project.getNature(Nature.NATURE_ID));
		project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
		assertNull(project.getNature(Nature.NATURE_ID));
	}

	@Test public void detectError() throws Exception {
		final IFile rules = project.getFile("rules.jdep");
		rules.create(new ByteArrayInputStream(("" //
				+ "lib $default contains java.**\n" //
				+ "comp com.example.core.**\n" //
				+ "comp com.example.ui.**\n" //
		).getBytes()), true, null);
		final IFile config = project.getFile("edepchk.conf");
		config.create(new ByteArrayInputStream("bin/\n  rules.jdep\n".getBytes()), true, null);

		final IPackageFragment core = testProject.createPackage("com.example.core");
		testProject.createType(core, "Core.java", "public class Core {}");
		testProject.createType(core, "Core2.java", "public class Core2 {}");
		final IPackageFragment ui = testProject.createPackage("com.example.ui");
		final IResource uiRes = testProject.createType(ui, "UI.java",
				"public class UI extends com.example.core.Core {}").getResource();

		assertEquals(0, project.findMarkers(Builder.MARKER_TYPE, true, IResource.DEPTH_INFINITE).length);

		project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
		{
			final IMarker[] markers = project.findMarkers(Builder.MARKER_TYPE, true, IResource.DEPTH_INFINITE);
			assertEquals(1, markers.length);
			assertEquals(uiRes.getName(), markers[0].getResource().getName());
		}

		// update through file system
		final FileWriter fw = new FileWriter(uiRes.getLocation().toFile());
		try {
			fw.write("package com.example.ui;\n\n\n\n\npublic class UI extends com.example.core.Core {\n public com.example.core.Core2 core;\n}");
		} finally {
			fw.close();
		}
		uiRes.refreshLocal(IResource.DEPTH_INFINITE, null);

		project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
		{
			final IMarker[] markers = project.findMarkers(Builder.MARKER_TYPE, true, IResource.DEPTH_INFINITE);
			assertEquals(2, markers.length);
			assertEquals(uiRes.getName(), markers[0].getResource().getName());
			assertEquals(uiRes.getName(), markers[1].getResource().getName());
		}
	}

	@Test public void reportInheritedMemberAccess() throws Exception {
		final IFile rules = project.getFile("rules.jdep");
		rules.create(new ByteArrayInputStream(("" //
				+ "lib $default contains java.lang.**\n" //
				+ "comp com.example.**\n" //
		).getBytes()), true, null);
		final IFile config = project.getFile("edepchk.conf");
		config.create(new ByteArrayInputStream("bin/\n  rules.jdep\n".getBytes()), true, null);

		final IPackageFragment pkg = testProject.createPackage("com.example");
		testProject.createType(pkg, "Test.java", "public class Test {\n" //
				+ "public java.io.File file;\n" //
				+ "public void print() {\n" //
				+ "  System.out.println();\n" //
				+ "}\n" //
				+ "}");

		project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
		final IMarker[] markers = project.findMarkers(Builder.MARKER_TYPE, true, IResource.DEPTH_INFINITE);
		assertEquals(2, markers.length);
		final IMarker m0 = markers[0];
		assertEquals(
				"Access to java.io.File denied by scope 'com.example' in ruleset '/home/peo/dev/junit-workspace/Project-1/rules.jdep'.",
				m0.getAttribute("message"));
		final int s0 = m0.getAttribute("charStart", -1);
		final int e0 = m0.getAttribute("charEnd", -1);
		final IMarker m1 = markers[1];
		assertEquals(
				"Access to java.io.PrintStream.println denied by scope 'com.example' in ruleset '/home/peo/dev/junit-workspace/Project-1/rules.jdep'.",
				m1.getAttribute("message"));
		final int s1 = m1.getAttribute("charStart", -1);
		final int e1 = m1.getAttribute("charEnd", -1);
		// Check that the call to println() was flagged properly _after_ the ref to File,
		// not across the entire class or just for the class name.
		assertTrue(s0 < e0);
		assertTrue(e0 < s1);
		assertTrue(s1 < e1);
	}

}
