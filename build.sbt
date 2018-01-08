configs(IntegrationTest)
Defaults.itSettings
val TestAndIntegrationTest = "test,it"

organization in ThisBuild := "com.contxt"
scalaVersion in ThisBuild := "2.11.8"

val slf4j = "org.slf4j" % "slf4j-api" % "1.7.21"
val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
val amazonKinesisClient = "com.amazonaws" % "amazon-kinesis-client" % "1.8.8"
val amazonKinesisProducer = "com.amazonaws" % "amazon-kinesis-producer" % "0.12.8"
val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1"
val scalaMock = "org.scalamock" %% "scalamock-scalatest-support" % "3.6.0"
val akkaStream = "com.typesafe.akka" %% "akka-stream" % "2.5.6"
val akkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.6"

libraryDependencies ++= Seq(
  slf4j,
  akkaStream,
  amazonKinesisClient,
  amazonKinesisProducer % IntegrationTest,
  logback % TestAndIntegrationTest,
  scalaTest % TestAndIntegrationTest,
  scalaMock % Test,
  akkaStreamTestkit % Test
)