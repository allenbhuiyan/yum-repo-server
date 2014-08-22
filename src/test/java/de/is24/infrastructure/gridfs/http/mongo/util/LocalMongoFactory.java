package de.is24.infrastructure.gridfs.http.mongo.util;

import com.google.common.annotations.VisibleForTesting;
import com.mongodb.BasicDBList;
import com.mongodb.DB;
import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.ArtifactStoreBuilder;
import de.flapdoodle.embed.mongo.config.DownloadConfigBuilder;
import de.flapdoodle.embed.mongo.config.IMongodConfig;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.RuntimeConfigBuilder;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.config.IRuntimeConfig;
import de.flapdoodle.embed.process.io.directories.FixedPath;
import de.flapdoodle.embed.process.io.directories.PlatformTempDir;
import de.flapdoodle.embed.process.io.progress.LoggingProgressListener;
import de.is24.infrastructure.gridfs.http.utils.retry.RetryUtils;

import java.io.File;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.mongodb.BasicDBObjectBuilder.start;
import static de.flapdoodle.embed.mongo.Command.MongoD;
import static de.is24.infrastructure.gridfs.http.utils.retry.RetryUtils.execute;


public class LocalMongoFactory {
  private static final String TEMP_DIR = new PlatformTempDir().asFile().getAbsolutePath();
  @VisibleForTesting
  static final FixedPath MONGO_DOWNLOAD_FOLDER = new FixedPath(TEMP_DIR + File.separator + ".embedded-mongo");
  private static final Logger LOGGER = Logger.getLogger(LocalMongoFactory.class.getCanonicalName());
  public static final String MONGO_USERNAME = "reposerver";
  public static final String MONGO_PASSWORD = "reposerver";
  public static final String MONGO_DB_NAME = "rpm_db";

  @VisibleForTesting
  static MongodStarter createMongoStarter() {
    de.flapdoodle.embed.process.config.store.DownloadConfigBuilder downloadConfigBuilder =
      new DownloadConfigBuilder() //
      .defaultsForCommand(MongoD) //
      .progressListener(new LoggingProgressListener(LOGGER, Level.INFO)) //
      .downloadPath("http://fastdl.mongodb.org/") //
      .artifactStorePath(MONGO_DOWNLOAD_FOLDER); //
    de.flapdoodle.embed.process.store.ArtifactStoreBuilder download =
      new ArtifactStoreBuilder() //
      .defaults(MongoD) //
      .download(downloadConfigBuilder);

    IRuntimeConfig runtimeConfig =
      new RuntimeConfigBuilder() //
      .defaultsWithLogger(MongoD, LOGGER) //
      .artifactStore(download).build();
    return MongodStarter.getInstance(runtimeConfig);
  }

  public static MongoProcessHolder createMongoProcess() throws Throwable {
    final MongodStarter runtime = createMongoStarter();

    return execute().maxTries(3).wait(3).command(new RetryUtils.Retryable<MongoProcessHolder>() {
        @Override
        public MongoProcessHolder run() throws Throwable {
          IMongodConfig config = new MongodConfigBuilder().version(getVersion()).build();
          MongodExecutable mongodExecutable = runtime.prepare(config);
          MongodProcess mongoProcess = mongodExecutable.start();
          prepareDatabase(config.net().getPort());

          System.setProperty("mongodb.port", "" + config.net().getPort());
          System.setProperty("mongodb.serverlist", "localhost");
          System.setProperty("mongodb.db.user", MONGO_USERNAME);
          System.setProperty("mongodb.db.pass", MONGO_PASSWORD);

          return new MongoProcessHolder(mongodExecutable, mongoProcess, config.net().getPort());
        }

        private Version getVersion() {
          String version = System.getProperty("embedded.mongodb.version", Version.Main.PRODUCTION.asInDownloadPath());
          return Version.valueOf("V" + version.replaceAll("\\.", "_"));
        }

        private void prepareDatabase(final int mongoPort) throws UnknownHostException {
          Mongo mongo = new MongoClient("localhost", mongoPort);
          createDBUser(mongo.getDB(MONGO_DB_NAME));
        }

        private void createDBUser(DB db) {
          BasicDBList roles = new BasicDBList();
          roles.add("dbAdmin");
          db.command(start("createUser", MONGO_USERNAME)
              .add("pwd", MONGO_PASSWORD)
              .add("roles", roles).get());
        }
    });

  }
}
