package ch.parren.edepchk.core.builder;

import org.eclipse.jdt.core.compiler.CompilationParticipant;

/** Does nothing but force Eclipse to load my plugin prior to any build. */
public final class Compiler extends CompilationParticipant {}
