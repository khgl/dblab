Legobase Compiler
=================

LegoBase is an efficient query engine in Scala programming language. This project
hosts the transformers and compilation pipeline of LegoBase.

Installation
============

The LegoBase compiler requires the SC Pardis Compiler (https://github.com/epfldata/sc), C.Scala Library and C.Scala Compiler; 
the projects `sc-pardis-compiler`, `sc-c-scala-lib`, and `sc-c-scala-deep`.

If you have access to the sc repository follow these steps:
1. Clone this project and checkout to the desired branch. Then, in sbt interactive mode,
run `project sc-pardis-compiler` for 
going to the Pardis Compiler project.
2. Run `publish-local` on the `root` project of Pardis.
Then run `sc-c-scala-lib/publish-local` and `sc-c-scala-deep/publish-local` in the sbt console
of the Pardis project.

sbt takes care of fetching the necessary dependencies. 


Testing
=======
For testing the compiler (Scala generated code), first you have to generate the code. 
For that purpose you have to go to the `lego-compiler` project using `project lego-compiler`.
Then, for generating query X with scaling factor N, you have to run the following command:
`generate-test DATA_FOLDER N QX`
For testing the correctness you have to copy the generated file into the `test` folder of the `lego-compiler` project.
Then you have to run the following command:
`test:run DATA_FOLDER N QX`

For testing all TPCH queries with the Scala code generator, in the `lego-compiler` project,
you should run `generate-test DATA_FOLDER N testsuite`.
Then you should publish the `lego-core` project using `lego-core/publish-local`.
Afterwards, you have to set your environment variable `SCALA_PATH` to the folder which contains `scalac`.
Finally, you have to go to the `generator-out` folder and run `./run_scala.sh DATA_FOLDER N`.
