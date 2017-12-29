
# Class Data Sharing

Class Data Sharing (CDS) is a feature to improve startup performance and reduce the memory footprint of the HotSpot JVM by storing the preprocessed metadata of system classes to disk and sharing them between virtual machines running on the same host. It was introduced as early as 2004 with the first release of [Oracle's Java 5 release](https://docs.oracle.com/javase/1.5.0/docs/guide/vm/class-data-sharing.html) and later became available in the first version of OpenJDK (i.e. [jdk6](http://hg.openjdk.java.net/jdk6/jdk6)). During the last years, this feature has been constantly extended and improved. Oracle JDK 9 introduced [AppCDS](https://docs.oracle.com/javase/9/tools/java.htm#JSWOR-GUID-31503FCE-93D0-4175-9B4F-F6A738B2F4C4) as a commercial feature only, which additionally allows the caching and sharing of application classes, strings and symbols. This commercial feature will be open sourced and made freely available in OpenJDK 10 by [JEP 310: Application Class-Data Sharing](http://openjdk.java.net/jeps/310).

Starting with their Java SDK 6.0, IBM also started to support [Class Data Sharing](https://www.ibm.com/support/knowledgecenter/en/SSYKE2_6.0.0/com.ibm.java.doc.user.lnx.60/user/shc_overview.html). They not only supported system class but also classes loaded by the application class loader (a.k.a. system class loader), classes loaded by [custom class loaders](https://www.ibm.com/support/knowledgecenter/SSYKE2_6.0.0/com.ibm.java.doc.user.lnx.60/user/adaptingclassloaders.html?view=kc#adaptingclassloaders) and even ahead-of-time (AOT) compiled code (which was not shared between JVMs). While IBM J9's CDS addresses similar problems like the HotSpot class data sharing feature in Oracle/OpenJDK, it is technically a completely independent implementation. Although it is much more mature and elaborate compared to the HotSpot implementation, J9's CDS hasn't attracted that much attention simply because Oracle/OpenJDK has been the predominant JVM in the past decade. But that might change with the open sourcing of the IBM J9 JVM within the [Eclipse OpenJ9](https://www.eclipse.org/openj9/) project. 

This article will only cover the HotSpot CDS functionality and implementation. If you're interested in the J9/OpenJ9 implementation you may have a look at the [latest documentation](https://www.ibm.com/support/knowledgecenter/en/SSYKE2_9.0.0/com.ibm.java.multiplatform.90.doc/user/classdatasharing.html), watch the presentation about "OpenJ9: Under the hood of the next open source JVM" from [Geekon 2017 / Krakow](https://www.youtube.com/watch?v=3VporpPlDds) or [Devoxx 2017 / Poland](https://www.youtube.com/watch?v=96XoG6xcnys) or simply [download OpenJ9](https://adoptopenjdk.net/releases.html?variant=openjdk9-openj9) and run `java -Xshareclasses:help` :wink:





<!--  LocalWords:  CDS startup HotSpot JVM preprocessed metadata jdk
 -->
<!--  LocalWords:  OpenJDK SDK adaptingclassloaders AOT JVMs OpenJ VM
 -->
<!--  LocalWords:  Geekon Devoxx Xshareclasses AppCDS JEP JSWOR GUID
 -->
<!--  LocalWords:  FCE
 -->
