
# Class Data Sharing

Class Data Sharing (CDS) is a feature to improve startup performance and reduce the memory footprint of the HotSpot JVM by storing the preprocessed metadata of system classes to disk and sharing them between virtual machines running on the same host. It was introduced as early as 2004 with the first release of [Oracle's Java 5 release](https://docs.oracle.com/javase/1.5.0/docs/guide/vm/class-data-sharing.html) and later became available in the first version of OpenJDK (i.e. [jdk6](http://hg.openjdk.java.net/jdk6/jdk6)). During the last years, this feature has been constantly extended and improved. Oracle JDK 9 introduced [AppCDS](https://docs.oracle.com/javase/9/tools/java.htm#JSWOR-GUID-31503FCE-93D0-4175-9B4F-F6A738B2F4C4) as a commercial feature only, which additionally allows the caching and sharing of application classes, strings and symbols. This commercial feature will be open sourced and made freely available in OpenJDK 10 by [JEP 310: Application Class-Data Sharing](http://openjdk.java.net/jeps/310).

Starting with their Java SDK 6.0, IBM also started to support [Class Data Sharing](https://www.ibm.com/support/knowledgecenter/en/SSYKE2_6.0.0/com.ibm.java.doc.user.lnx.60/user/shc_overview.html). They not only supported system class but also classes loaded by the application class loader (a.k.a. system class loader), classes loaded by [custom class loaders](https://www.ibm.com/support/knowledgecenter/SSYKE2_6.0.0/com.ibm.java.doc.user.lnx.60/user/adaptingclassloaders.html?view=kc#adaptingclassloaders) and even ahead-of-time (AOT) compiled code (which was not shared between JVMs). While IBM J9's CDS addresses similar problems like the HotSpot class data sharing feature in Oracle/OpenJDK, it is technically a completely independent implementation. Although it is much more mature and elaborate compared to the HotSpot implementation, J9's CDS hasn't attracted that much attention simply because Oracle/OpenJDK has been the predominant JVM in the past decade. But that might change with the open sourcing of the IBM J9 JVM within the [Eclipse OpenJ9](https://www.eclipse.org/openj9/) project. 

This article will only cover the HotSpot CDS functionality and implementation. If you're interested in the J9/OpenJ9 implementation you may have a look at the [latest documentation](https://www.ibm.com/support/knowledgecenter/en/SSYKE2_9.0.0/com.ibm.java.multiplatform.90.doc/user/classdatasharing.html), watch the presentation about "OpenJ9: Under the hood of the next open source JVM" from [Geekon 2017 / Krakow](https://www.youtube.com/watch?v=3VporpPlDds) or [Devoxx 2017 / Poland](https://www.youtube.com/watch?v=96XoG6xcnys) or simply [download OpenJ9](https://adoptopenjdk.net/releases.html?variant=openjdk9-openj9) and run `java -Xshareclasses:help` :wink:


# Using CDS

In Oracle J2SE 5.0 the usage of CDS was quite restricted - the feature was only available in the Client VM when running with the Serial GC. Meanwhile, [Oracle/OpenJDK 9 CDS](https://docs.oracle.com/javase/9/vm/class-data-sharing.htm#JSJVM-GUID-0260F857-A70E-4399-A1DF-A5766BE33285) also supports the G1, Serial, Parallel, and ParallelOld GCs with the Server VM. The following examples are all based on OpenJDK 9.

Before CDS can be used, the so called *Shared Archive* has to be created first:

``` shell
$ java -Xshare:dump
Allocated shared space: 50577408 bytes at 0x0000000800000000
Loading classes to share ...
Loading classes to share: done.
Rewriting and linking classes ...
Rewriting and linking classes: done
Number of classes 1197
    instance classes   =  1183
    obj array classes  =     6
    type array classes =     8
Updating ConstMethods ... done. 
Removing unshareable information ... done. 
ro space:   5332520 [ 30.5% of total] out of  10485760 bytes [ 50.9% used] at 0x0000000800000000
rw space:   5630560 [ 32.2% of total] out of  10485760 bytes [ 53.7% used] at 0x0000000800a00000
md space:     98976 [  0.6% of total] out of   4194304 bytes [  2.4% used] at 0x0000000801400000
mc space:     34053 [  0.2% of total] out of    122880 bytes [ 27.7% used] at 0x0000000801800000
st space:     12288 [  0.1% of total] out of     12288 bytes [100.0% used] at 0x00000000fff00000
od space:   6363752 [ 36.4% of total] out of  20971520 bytes [ 30.3% used] at 0x000000080181e000
total   :  17472149 [100.0% of total] out of  46272512 bytes [ 37.8% used]
```

In this simplest form, the `-Xshare:dump` command will use a default class list `<java_home>/lib/classlist` which was created at JDK build time and create the shared class archive under `<java_home>/lib/server/classes.jsa`:

``` shell
$ (cd <java_home> && ls -o lib/classlist lib/server/classes.jsa)
-rw-rw-r-- 1 simonis    40580 Okt 23 19:06 lib/classlist
-r--r--r-- 1 simonis 17485824 Dez 30 11:39 lib/server/classes.jsa
```

`<java_home>/lib/classlist` is a text file which contains the list of classes (one class per line, in [internal form](https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.2.1)) which should be added to the shared class archive:

``` shell
$ head -5 <java_home>/lib/classlist
java/lang/Object
java/lang/String
java/io/Serializable
java/lang/Comparable
java/lang/CharSequence
```

As mentioned before, the `classlist` file is created at JDK build-time (controlled by the `--enable-generate-classlist`/`--disable-generate-classlist` flag which defaults to true on platforms which support CDS) by running a simple Java program called [`HelloClasslist`](http://hg.openjdk.java.net/jdk/jdk/file/tip/make/jdk/src/classes/build/tools/classlist/HelloClasslist.java) (see [GenerateLinkOptData.gmk](http://hg.openjdk.java.net/jdk/jdk/file/tip/make/GenerateLinkOptData.gmk)) with the `-XX:DumpLoadedClassList=<classlist_file>` option to collect the system classes it uses. Of course, `HelloClasslist` is only a simple approximation for the amount of system classes a typical, small Java application will use.

We can now take a simple `HelloCDS` Java program and run it with `-Xshare:on` to take advantage of the shared class archive:

``` java
package io.simonis;
public class HelloCDS {
  public static void main(String[] args) {
    System.out.println("Hello CDS");
  }
}
```

`-Xshare:on` instructs to VM to use the shared class from the default location at `<java_home>/lib/server/classes.jsa`. If the archive hasn't been created or is corrupted, the VM will exit with an error:

``` shell
$ rm -f <java_home>/lib/server/classes.jsa
$ java -Xshare:on HelloCDS 
An error has occurred while processing the shared archive file.
Specified shared archive not found.
Error occurred during initialization of VM
Unable to use shared archive.
```

We could instead use `-Xshare:auto` which behaves like `-Xshare:on` if the shared archive is available and automatically falls back to `-Xshare:off` if the shared archive can not be found or used. After recreating the archive, our program will run just fine, but how can we verify which classes get really loaded right from the shared class archive?

``` shell
$ java -Xshare:on HelloCDS 
Hello CDS
```

Here the class loading log comes in quite handy, because it not only reports which classes are being loaded, but also where they get loaded from in the `source:` section:

``` shell
$ java -Xshare:on -Xlog:class+load io.simonis.HelloCDS 
[0.011s][info][class,load] opened: /share/output-jdk9-dev-opt/images/jdk/lib/modules
[0.024s][info][class,load] java.lang.Object source: shared objects file
[0.024s][info][class,load] java.io.Serializable source: shared objects file
[0.024s][info][class,load] java.lang.Comparable source: shared objects file
...
```

In order to check which classes haven't been loaded from the archive, we can grep for all log entries which don't contain the term `shared objects file`:

``` shell
$ java -Xshare:on -Xlog:class+load HelloCDS | grep --invert-match "shared objects file"
[0.014s][info][class,load] opened: /share/output-jdk9-dev-opt/images/jdk/lib/modules
[0,073s][info][class,load] java.util.ImmutableCollections$ListN source: jrt:/java.base
[0,079s][info][class,load] jdk.internal.module.ModuleHashes$Builder source: jrt:/java.base
[0,080s][info][class,load] jdk.internal.module.ModuleHashes$HashSupplier source: jrt:/java.base
[0,080s][info][class,load] jdk.internal.module.SystemModuleFinder$2 source: jrt:/java.base
[0,128s][info][class,load] jdk.internal.loader.URLClassPath$FileLoader source: jrt:/java.base
[0,140s][info][class,load] jdk.internal.loader.URLClassPath$FileLoader$1 source: jrt:/java.base
[0,149s][info][class,load] io.simonis.HelloCDS source: file:/FOSDEM2018/git/examples/bin/
Hello CDS
```

As we can see, there are just a few classes from the base module which still get loaded directly from the java runtime image (i.e. from the `lib/modules` file). Obviously they were not referenced or used by the `HelloClasslist` application which was used to generate the default class list under `<java_home>/lib/classlist`. But we can of course generate a new, individual class list for our `HelloCDS` application, much in the same way the default class list was generated at build time (by using the `-XX:DumpLoadedClassList=<classlist_file>` option). Afterwards we use that class list (by using the `-XX:SharedClassListFile=<classlist_file>`) to generate a new, application specific shared archive. If we do not explicitly specify the location of the new archive file with the `-XX:SharedArchiveFile=<classlist_file>` option (which is a diagnostic option so we need `-XX:+UnlockDiagnosticVMOptions` as well) the default archive at `<java_home>/lib/server/classes.jsa` will be silently overwritten.

``` shell
$ java -XX:DumpLoadedClassList=/tmp/HelloCDS.cls io.simonis.HelloCDS
$ java -XX:SharedClassListFile=/tmp/HelloCDS.cls -XX:+UnlockDiagnosticVMOptions -XX:SharedArchiveFile=/tmp/HelloCDS.jsa -Xshare:dump
Allocated shared space: 50577408 bytes at 0x0000000800000000
Loading classes to share ...
Loading classes to share: done.
Rewriting and linking classes ...
Rewriting and linking classes: done
Number of classes 522
    instance classes   =   508
    obj array classes  =     6
    type array classes =     8
Updating ConstMethods ... done. 
Removing unshareable information ... done. 
ro space:   2498200 [ 31.5% of total] out of  10485760 bytes [ 23.8% used] at 0x0000000800000000
rw space:   2500208 [ 31.6% of total] out of  10485760 bytes [ 23.8% used] at 0x0000000800a00000
md space:     68760 [  0.9% of total] out of   4194304 bytes [  1.6% used] at 0x0000000801400000
mc space:     34053 [  0.4% of total] out of    122880 bytes [ 27.7% used] at 0x0000000801800000
st space:      8192 [  0.1% of total] out of      8192 bytes [100.0% used] at 0x00000000fff00000
od space:   2810480 [ 35.5% of total] out of  20971520 bytes [ 13.4% used] at 0x000000080181e000
total   :   7919893 [100.0% of total] out of  46268416 bytes [ 17.1% used]
```

As you can see, the new archive contains fewer classes (522 compared to 1197 before). We can use the new archive by passing it to the VM with the `-XX:SharedArchiveFile=<classlist_file>` option:

``` shell
$ /share/output-jdk9-dev-opt/images/jdk/bin/java -Xshare:on -Xlog:class+load -XX:+UnlockDiagnosticVMOptions -XX:SharedArchiveFile=/tmp/HelloCDS.jsa io.simonis.HelloCDS | grep --invert-match "shared objects file"
[0.010s][info][class,load] opened: /share/output-jdk9-dev-opt/images/jdk/lib/modules
[0,176s][info][class,load] io.simonis.HelloCDS source: file:/FOSDEM2018/git/examples/bin/
Hello CDS
```

This time all the classes except our application class `io.simonis.HelloCDS` have been loaded from the shared archive! 

## CDS performance benefits

So let's see if CDS makes any difference if it comes to start-up performance by using the `time` utility to measure the elapsed wall clock time (the output below actually shows the avarage of five runs in a row):

``` shell
$ time -f "%e sec\n" java -Xshare:off -XX:+UnlockDiagnosticVMOptions -XX:SharedArchiveFile=/tmp/HelloCDS.jsa io.simonis.HelloCDS 
Hello CDS
0.162 sec
$ time -f "%e sec\n" java -Xshare:on -XX:+UnlockDiagnosticVMOptions -XX:SharedArchiveFile=/tmp/HelloCDS.jsa io.simonis.HelloCDS 
Hello CDS
0.148 sec
```

So it seems like CDS gives us about 9% better performance altough we've actually measured the overally execution time here. We can do a little better by measuring the time it needs until our application class gets loaded (again showing the avarage  of five consecutive runs):

``` shell
$ time -f "%e sec\n" java -Xshare:off -XX:+UnlockDiagnosticVMOptions -XX:SharedArchiveFile=/tmp/HelloCDS.jsa -Xlog:class+load io.simonis.HelloCDS | grep HelloCDS
[0,164s][info][class,load] io.simonis.HelloCDS source: file:/FOSDEM2018/git/examples/bin/
0.178 sec
$ time -f "%e sec\n" java -Xshare:on -XX:+UnlockDiagnosticVMOptions -XX:SharedArchiveFile=/tmp/HelloCDS.jsa -Xlog:class+load io.simonis.HelloCDS | grep HelloCDS
[0,143s][info][class,load] io.simonis.HelloCDS source: file:/FOSDEM2018/git/examples/bin/
0.160 sec
```

Notice that the overall execution time has slightly increased because of the additional logging but the time until our `HelloCDS` class gets loaded is about 13% faster with CDS compared to the default run without CDS.

## CDS memory savings


## CDS summary

Finally, it should be mentioned that the each of the various `-Xshare` options there exists a corresponding extended `-XX:` option as indicated in the following table:

Short Form | Long Form
---------- | ---------
`-Xshare:dump` | `-XX:+DumpSharedSpaces` (implies `-Xint`)
`-Xshare:on` | `-XX:+UseSharedSpaces` `-XX:+RequireSharedSpaces`
`-Xshare:auto` | `-XX:+UseSharedSpaces` `-XX:-RequireSharedSpaces`
`-Xshare:off` | `-XX:-UseSharedSpaces` `-XX:-RequireSharedSpaces`

<!--

/* Shared spaces */                                                       \
                                                                            \
  product(bool, UseSharedSpaces, true,                                      \
          "Use shared spaces for metadata")                                 \
                                                                            \
  product(bool, VerifySharedSpaces, false,                                  \
          "Verify shared spaces (false for default archive, true for "      \
          "archive specified by -XX:SharedArchiveFile)")                    \
                                                                            \
  product(bool, RequireSharedSpaces, false,                                 \
          "Require shared spaces for metadata")                             \
                                                                            \
  product(bool, DumpSharedSpaces, false,                                    \
          "Special mode: JVM reads a class list, loads classes, builds "    \
          "shared spaces, and dumps the shared spaces to a file to be "     \
          "used in future JVM runs")                                        \
                                                                            \
  product(bool, PrintSharedArchiveAndExit, false,                           \
          "Print shared archive file contents")                             \
                                                                            \
  product(bool, PrintSharedDictionary, false,                               \
          "If PrintSharedArchiveAndExit is true, also print the shared "    \
          "dictionary")                                                     \
                                                                            \
  product(size_t, SharedBaseAddress, LP64_ONLY(32*G)                        \
          NOT_LP64(LINUX_ONLY(2*G) NOT_LINUX(0)),                           \
          "Address to allocate shared memory region for class data")        \
          range(0, SIZE_MAX)                                                \
                                                                            \
  product(bool, UseAppCDS, false,                                           \
          "Enable Application Class Data Sharing when using shared spaces") \
          writeable(CommandLineOnly)                                        \
                                                                            \
  product(ccstr, SharedArchiveConfigFile, NULL,                             \
          "Data to add to the CDS archive file")                            \
                                                                            \
  product(uintx, SharedSymbolTableBucketSize, 4,                            \
          "Average number of symbols per bucket in shared table")           \
          range(2, 246)                                                     \
                                                                            \
  diagnostic(bool, IgnoreUnverifiableClassesDuringDump, true,              \
          "Do not quit -Xshare:dump even if we encounter unverifiable "     \
          "classes. Just exclude them from the shared dictionary.")         \
                                                                            \

  product(ccstr, DumpLoadedClassList, NULL,                                 \
          "Dump the names all loaded classes, that could be stored into "   \
          "the CDS archive, in the specified file")                         \
                                                                            \
  product(ccstr, SharedClassListFile, NULL,                                 \
          "Override the default CDS class list")                            \
                                                                            \
  diagnostic(ccstr, SharedArchiveFile, NULL,                                \
          "Override the default location of the CDS archive file")          \
                                                                            \
  product(ccstr, ExtraSharedClassListFile, NULL,                            \
          "Extra classlist for building the CDS archive file")              \


Summary: Obsoleted SharedReadOnlySize, SharedMiscCodeSize, SharedMiscDataSize and SharedReadWriteSize


-XX:+PrintSharedSpaces === -Xlog:cds=info. The WizardMode and Verbose statements correspond to "trace"
Additionally, the tag combinations "cds+hashtables", "cds+verification", and "cds+vtables=debug"


ConstantPool*p ==>[ _vptr    ] =======> [ vtable slot 0 ]
                   [ field #0 ]          [ vtable slot 1 ]
                   [ field #1 ]          [ vtable slot 2 ]
                   [ field #2 ]          [ vtable slot 3 ]
                   [ ....     ]          [ vtable slot 4]
                                         [ vtable slot 5 ]
                                         [ ...           ]

RFR[S] 8005165 Platform-independent C++ vtables for CDS
http://mail.openjdk.java.net/pipermail/hotspot-dev/2017-March/thread.html#26063

-->

<!--  LocalWords:  CDS startup HotSpot JVM preprocessed metadata jdk
 -->
<!--  LocalWords:  OpenJDK SDK adaptingclassloaders AOT JVMs OpenJ VM
 -->
<!--  LocalWords:  Geekon Devoxx Xshareclasses AppCDS JEP JSWOR GUID
 -->
<!--  LocalWords:  FCE GC JSJVM DF ParallelOld GCs Xshare unshareable
 -->
<!--  LocalWords:  ConstMethods ro rw md mc fff od cd classlist Okt
 -->
<!--  LocalWords:  simonis Dez HelloClasslist GenerateLinkOptData gmk
 -->
<!--  LocalWords:  DumpLoadedClassList HelloCDS io args Xlog runtime
 -->
<!--  LocalWords:  SharedClassListFile SharedArchiveFile cp Xint
 -->
<!--  LocalWords:  UnlockDiagnosticVMOptions DumpSharedSpaces
 -->
<!--  LocalWords:  UseSharedSpaces RequireSharedSpaces
 -->
