Integrates [JDepChk](http://code.google.com/p/jdepchk/), the JVM class file dependency checker, directly into Eclipse. Highlights:

  * Smart highlighting of error locations (uses Eclipse's code search).
  * Fast incremental builder. Overhead is negligible.
  * Configured through per-project config file.

Install EDepChk into Eclipse via our [update site](http://edepchk.eclipselabs.org.codespot.com/hg.update-site/site.xml).

See UsingIt for details.

EDepChk was inspired by [Eclipse Macker](http://eclipse-macker.sourceforge.net/), but completely rewritten from scratch with focus on low overhead, better error highlighting, and more versatile configuration.