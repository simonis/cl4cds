/*
 * Copyright (c) 2017, Volker Simonis
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.simonis;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * @author simonis
 *
 */
public class cl4cds {

  private static final boolean DBG = Boolean.getBoolean("io.simonis.cl4cds.debug");
  private static final boolean DumpFromClassFiles = Boolean.getBoolean("io.simonis.cl4cds.dumpFromClassFile");
  
  private enum Status {
    OK, ERROR, PRE_15, LOAD_ERROR, ZIP_ERROR, JAR_ERROR
  }

  public static void main(String... args) {
    BufferedReader in = null;
    PrintStream out = null;
    if (args.length == 0) {
      in = new BufferedReader(new InputStreamReader(System.in));
      out = System.out;
    }
    else if (args.length == 1) {
      if ("-h".equals(args[0]) ||
          "--help".equals(args[0]) ||
          "/?".equals(args[0])) {
        help(0);
      }

      try {
        in = Files.newBufferedReader(Paths.get(args[0]));
      } catch (IOException e) {
        error("Cant open \"" + args[0] + "\" for reading!");
      }
      out = System.out;
    }
    else if (args.length == 2) {
      try {
        in = Files.newBufferedReader(Paths.get(args[0]));
      } catch (IOException e) {
        error("Cant open \"" + args[0] + "\" for reading!");
      }
      try {
        out = new PrintStream(args[1]);
      } catch (IOException e) {
        error("Cant open \"" + args[0] + "\" for writing!");
      }
    }

    convert(in, out);
  }

  private static void convert(BufferedReader in, PrintStream out) {
    // Pattern for JVM class names (see JVMLS §4.2)
    final String uqNameP = "((?:[^,;/\\[]+?\\.)*(?:[^,;/\\[]+?))";
    final String timeDecoP = "\\[.+?\\]";
    final String hexP = " (0x[0-9a-f]+)";
    final String hexesP = " (0x[0-9a-f]+(?: 0x[0-9a-f]+)*)";
    final String infoDecoP = "\\[info *\\Q][class,load]\\E ";
    final String debugDecoP = "\\[debug *\\Q][class,load]\\E ";
    Pattern firstLineP = Pattern.compile(timeDecoP + infoDecoP + uqNameP + " source: (.+)");
    Pattern secondLineP = Pattern.compile(timeDecoP + debugDecoP + 
        " klass:" + hexP + " super:" + hexP + "(?: interfaces:" + hexesP + ")? loader: \\[(.+?)\\]" + ".+");
    if (DBG) {
      System.err.println("The following two patterns are used to match the -Xlog:class+load=trace output:");
      System.err.println("  " + firstLineP.toString());
      System.err.println("  " + secondLineP.toString());
    }
    Matcher firstLine = firstLineP.matcher("");
    Matcher secondLine = secondLineP.matcher("");
    try (in) {
      String line;
      Set<String> klassSet = new HashSet<>();
      Map<String, String> nameSourceMap = new HashMap<>();
      while((line = in.readLine()) != null) {
        if (firstLine.reset(line).matches()) {
          MatchResult mr1 = firstLine.toMatchResult();
          String name = mr1.group(1);
          String source = mr1.group(2);
          if (source.contains("__JVM_DefineClass__")) {
            // skip classes which have been generated dynamically at runtime
            System.err.println("Skipping " + name + " from " + source + " - reason: dynamically generated class");
            continue;
          }
          if ((line = in.readLine()) != null &&
              secondLine.reset(line).matches()) {
            MatchResult mr2 = secondLine.toMatchResult();
            String klass = mr2.group(1);
            String parent = mr2.group(2);
            String interf = mr2.group(3);
            String loader = mr2.group(4);

            if ("NULL class loader".equals(loader) ||
                loader.contains("jdk/internal/loader/ClassLoaders$PlatformClassLoader" /* && source == jrt image */) ||
                loader.contains("jdk/internal/loader/ClassLoaders$AppClassLoader" /* && source == jar file */)) {
              out.println(name.replace('.', '/') + " id: " + klass);
              klassSet.add(klass);
              nameSourceMap.put(name, source);
            }
            else {
              // Custom class loader (currently only supported if classes are loaded from jar files ?)
              String sourceFile = null;
              if (source != null && source.startsWith("file:") /* && source.endsWith(".jar") */) {
                sourceFile = source.substring("file:".length());
              }
              else if (source != null && source.startsWith("jar:file:") && source.endsWith("!/")) {
                sourceFile = source.substring("jar:file:".length(), source.length() - 2);
              }
              else {
                System.err.println("Skipping " + name + " from " + source + " - reason: unknown source format");
                continue;
              }
              if (!DumpFromClassFiles && Files.isDirectory(Paths.get(sourceFile))) {
                System.err.println("Skipping " + name + " from " + sourceFile + " - reason: loaded from class file (try '-Dio.simonis.cl4cds.dumpFromClassFile=true')");
                continue;
              }
              Status ret;
              if ((ret = checkClass(name.replace('.', '/'), sourceFile)) != Status.OK) {
                switch (ret) {
                case PRE_15 : 
                  System.err.println("Skipping " + name + " from " + sourceFile + " - reason: class is pre 1.5");
                  break;
                case LOAD_ERROR:
                case ZIP_ERROR:
                case JAR_ERROR:
                  System.err.println("Skipping " + name + " from " + sourceFile + " - reason: can't load (maybe generataed?))");
                  break;
                case ERROR:
                  System.err.println("Skipping " + name + " from " + sourceFile + " - reason: unknown source");
                  break;
                }
                continue;
              }
              List<String> deps = new LinkedList<>();
              deps.add(parent);
              if (interf != null) {
                deps.addAll(Arrays.asList(interf.split("\\s")));
              }
              if (klassSet.containsAll(deps)) {
                if (source.equals(nameSourceMap.get(name))) {
                  System.err.println("Skipping " + name + " from " + sourceFile + " - reason: already dumped");
                  continue;
                }
                out.print(name.replace('.', '/') + " id: " + klass + " super: " + parent);
                if (interf != null) {
                  out.print(" interfaces: " + interf);
                }
                out.println(" source: " + sourceFile);
                klassSet.add(klass);
                nameSourceMap.put(name, source);
              }
              else {
                System.err.println("Skipping " + name + " from " + sourceFile + " - reason: failed dependencies");
              }
            }
          }
        }
      }
    }
    catch (IOException ioe) {
      System.err.println("Error reading input file:\n" + ioe);
    }

  }

  private static Status checkClass(String name, String source) {
    if (Files.isDirectory(Paths.get(source))) {
      try (InputStream in = new FileInputStream(source + name + ".class")) {
        if (classVersion(in) < 49) return Status.PRE_15;
        return Status.OK;
      } catch (IOException e) {
        if (DBG) {
          System.err.println("Can't check class " + name + " from " + source + "\n" + e);
          return Status.LOAD_ERROR;
        }
      }
    }
    else if (source.endsWith(".jar") && Files.isRegularFile(Paths.get(source))) {
      try (JarFile jar = new JarFile(source)) {
        ZipEntry ze = jar.getEntry(name + ".class");
        if (ze != null) {
          if (classVersion(jar.getInputStream(ze)) < 49) return Status.PRE_15;
          return Status.OK;
        }
        else if (DBG) {
          System.err.println("Can't get zip entry " + name + ".class" + " from jar file " + source);
          return Status.ZIP_ERROR;
        }
      } catch (IOException e) {
        if (DBG) {
          System.err.println("Can't check class " + name + " from jar file " + source + "\n" + e);
          return Status.JAR_ERROR;
        }
      }
    }
    if (DBG) {
      System.err.println("Can't check " + name + " from " + source);
    }
    return Status.ERROR;
  }

  private static int classVersion(InputStream in) throws IOException {
    try (DataInputStream dis = new DataInputStream(in)) {
      int magic = dis.readInt();
      if (magic != 0xcafebabe) {
        if (DBG) {
          System.err.println("Invalid class file!");
          return 0;
        }
      }
      int minor = dis.readUnsignedShort();
      int major = dis.readUnsignedShort();
      return major;
    }
  }

  private static void error(String msg) {
    System.err.println(msg);
    help(-1);
  }

  private static void help(int status) {
    System.exit(status);
  }

}
