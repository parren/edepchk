## Installation ##

Use the [update site](http://edepchk.eclipselabs.org.codespot.com/hg.update-site/site.xml) to install EDepChk into Eclipse.

## Activation ##

Once installed, you need to configure it. EDepChk is unusual in that it has no UI. Instead, you activate and configure it via a configuration file. This file must reside in the root folder of your Java project and be called either `edepchk.conf` or `.edepchk`.

Whenever a Java project is rebuilt, EDepChk looks for one of these files. If found, it registers itself with the project as an additional builder (if necessary) and checks rule conformance according to the settings in the file. If neither file is found, it deregisters itself from the project (again only if necessary).

## Configuration File Format ##

Configuration files follow the [format defined by JDepChk](http://code.google.com/p/jdepchk/source/browse/src/main/java/ch/parren/jdepchk/help.txt#100), with a few additional twists:

  * Only `--classes` is supported for the classpath to scan. It's arguments must denote project-relative compiler output paths.
  * `--max-errors 9999` limits the number of errors EDepChk will add to Eclipse's problem list.

For example:

```
--max-errors 100

--scope main # main code base
  --classestemp/classes/main/
  --classes temp/classes/generated/
  --rules src/common.jdep
  --rules src/main.jdep

--scope tests # tests
  --classes temp/classes/tests/
  --rules src/common.jdep
  --rules src/tests.jdep
```

The output dirs and rule file references are relative to the project's root.

## Error Reporting ##

Rule violations are reported as normal Eclipse problems in the _Other_ category. For example:

```
Access to java.io.IOException denied by scope 'ch.parren.jdepchk.check' in ruleset 'rules.jdep'.
```


EDepChk tries hard to locate the error precisely in the source file when you click on an error. If it cannot (typically when references are through inherited members), it will highlight the entire region where the error must be.

Errors in rule files are also reported as Eclipse problems.

Errors in configuration files are currently reported rather clumsily as internal errors in Eclipse's log. I intend to improve this.