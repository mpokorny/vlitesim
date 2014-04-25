vlitesim - a VLITE Simulator
============================

Preparation
-----------

This application will run only on x86_64 platforms.

Ensure that your Java environment is set up properly. Primarily, this means
checking the value of the JAVA_HOME environment variable, and checking that the
"java" executable is in your shell path. The code has been tested only under
Java 7, although it may work on earlier versions.

Building
--------

If you have the distribution tarball, you may skip this section, and proceed to
[Installing](#installing).

vlitesim is built using the [sbt](http://www.scala-sbt.org/) tool. After
installing sbt, run `sbt universal:packageZipTarball` in the root directory of
the source tree to create the distribution tarball. The tarball will be found in
the "target/universal" sub-directory of the source tree.

Installing
----------

The distribution tarball may be installed in any directory. The contents of the
tarball will be extracted into a sub-directory, which will contain all jar
files, run scripts and configuration files.

Running
-------

Before running the application, you will need to edit the configuration file
"conf/application.conf". The provided file is a template, and must be changed to
reflect your desired configuration.

Ensure that the directory into which this distribution was extracted is
accessible from all hosts named in the configuration file.

Each running instance of the program acquires a "hostname" value at startup to
compare with the hostnames used in the configuration file. By default the value
of the HOSTNAME environment variable is used for this purpose. If you find that
the HOSTNAME environment variable doesn't have the proper value for this
purpose, set the VLITE_HOSTNAME environment variable to the proper value when
running "bin/vlitesim".

Single machine configuration
----------------------------

In this configuration all emulators run on the same machine as the simulator
controller.

Log on to the machine hosting the controller as defined in your configuration
file and run "bin/vlitesim" (from the distribution directory). You may have to
run this command as the root user (or the equivalent) when the raw Ethernet
device is accessed by the emulators because normal users usually do not have the
required permissions to use that device. If you see any error messages about
"libpcap" or "pcap", please see below. The emulators should soon be generating
VDIF frames. Use control-C to stop the controller and emulators.

Multiple machine configuration
------------------------------

In this configuration at least one emulator runs on a machine that is not
hosting the simulator controller. For simplicity in the following, we assume
that the machine hosting the controller is not hosting any emulators. (A hybrid
configuration in which some emulators are hosted by the controller machine, and
other emulators are not is also possible, but the operation of such
configurations is not described here.)

Log on to each machine hosting one or more emulators as defined in your
configuration file and run "bin/vlitesim". You may have to run this command as
the root user (or the equivalent) when the raw Ethernet device is accessed by
the emulators because normal users usually do not have the required permissions
to use that device. If you see any error messages about "libpcap" or "pcap",
please see below.

Log on to the machine hosting the controller as defined in your configuration
file and run "bin/vlitesim". This command never needs root privileges. The
emulators should soon be generating VDIF frames. Use control-C to stop the
controller and all VDIF frames from the emulators.

Note that the programs started on the emulator hosts may be left running at all
times (assuming that the configuration file does not change); they will not
generate any frames unless the controller is also running.

libpcap notes
-------------

The version numbers (SONAME) of libpcap libraries installed on various Linux
distributions are not consistent. However, in many cases, the libraries are
binary compatible. If the application prints out an error message about libpcap,
don't despair. In such cases, please contact me <mpokorny@nrao.edu>; it may be
the case that the application can easily be changed to link to the libpcap
version you have installed on your system.

--
Martin Pokorny <mpokorny@nrao.edu>
