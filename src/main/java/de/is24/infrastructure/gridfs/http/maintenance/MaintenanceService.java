package de.is24.infrastructure.gridfs.http.maintenance;

import com.mongodb.DBObject;
import de.is24.infrastructure.gridfs.http.domain.YumEntry;
import de.is24.infrastructure.gridfs.http.domain.yum.YumPackage;
import de.is24.infrastructure.gridfs.http.domain.yum.YumPackageReducedView;
import de.is24.infrastructure.gridfs.http.gridfs.StorageService;
import de.is24.infrastructure.gridfs.http.metadata.YumEntriesRepository;
import de.is24.infrastructure.gridfs.http.rpm.version.YumPackageVersionComparator;
import de.is24.infrastructure.gridfs.http.storage.FileDescriptor;
import de.is24.infrastructure.gridfs.http.storage.FileStorageItem;
import de.is24.infrastructure.gridfs.http.storage.FileStorageService;
import de.is24.infrastructure.gridfs.http.utils.MDCHelper;
import de.is24.util.monitoring.spring.TimeMeasurement;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.DocumentCallbackHandler;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.tx.MongoTx;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;

import static de.is24.infrastructure.gridfs.http.mongo.DatabaseStructure.GRIDFS_FILES_COLLECTION;
import static de.is24.infrastructure.gridfs.http.mongo.DatabaseStructure.YUM_ENTRY_COLLECTION;
import static java.util.stream.Collectors.toSet;
import static org.springframework.data.mongodb.core.query.Criteria.where;


@Service
@TimeMeasurement
public class MaintenanceService {
  private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceService.class);
  private final Filter obsoleteRpmFiler = new ObsoleteRpmFilter();
  private final Filter propagatableRpmFilter = new PropagatableRpmFilter();

  private YumPackageVersionComparator versionComparator = new YumPackageVersionComparator();
  private ScheduledExecutorService scheduledExecutorService;


  private YumEntriesRepository yumEntriesRepository;
  private FileStorageService fileStorageService;
  private StorageService storageService;

  private MongoTemplate mongoTemplate;
  private GridFsOperations gridFsTemplate;

  /* for AOP autoproxying */
  protected MaintenanceService() {
  }


  @Autowired
  public MaintenanceService(ScheduledExecutorService scheduledExecutorService,
                            YumEntriesRepository yumEntriesRepository,
                            FileStorageService fileStorageService, StorageService storageService,
                            MongoTemplate mongoTemplate,
                            GridFsOperations gridFsTemplate) {
    this.scheduledExecutorService = scheduledExecutorService;
    this.yumEntriesRepository = yumEntriesRepository;
    this.fileStorageService = fileStorageService;
    this.storageService = storageService;
    this.mongoTemplate = mongoTemplate;
    this.gridFsTemplate = gridFsTemplate;
  }

  public Set<YumPackageReducedView> getPropagatableRPMs(String targetRepo,
                                                        String sourceRepo) {
    return filterRPMsFromPropagationChain(propagatableRpmFilter, targetRepo, sourceRepo);
  }

  public Set<YumPackageReducedView> getObsoleteRPMs(String targetRepo,
                                                    String sourceRepo) {
    return filterRPMsFromPropagationChain(obsoleteRpmFiler, targetRepo, sourceRepo);
  }

  public void triggerDeletionOfObsoleteRPMs(String targetRepo, String sourceRepo) {
    DeleteObsoleteRPMsJob job = new DeleteObsoleteRPMsJob(sourceRepo,
      targetRepo);

    scheduledExecutorService.submit(job);
    LOGGER.info("triggered delete obsolete RPMs in propagation chain from {} to {}", sourceRepo, targetRepo);
  }

  public Map<ObjectId, YumPackageReducedView> getYumEntriesWithoutAssociatedFiles() {
    final Query query = new Query();
    query.fields().include("_id");


    CheckForMissingFsFilesCallbackHandler callbackHandler = new CheckForMissingFsFilesCallbackHandler();
    mongoTemplate.executeQuery(query, YUM_ENTRY_COLLECTION, callbackHandler);

    Map<ObjectId, YumPackageReducedView> result = new HashMap<>();
    for (ObjectId id : callbackHandler.getEntriesWithMissingFile()) {
      YumEntry yumEntry = yumEntriesRepository.findOne(id);
      result.put(yumEntry.getId(), new YumPackageReducedView(yumEntry.getYumPackage()));
    }
    return result;
  }

  public Set<FileStorageItem> getFilesWithoutYumEntry() {
    final Query query = new Query(where("metadata.arch").ne("repodata"));
    query.addCriteria(where("metadata.markedAsDeleted").exists(false));

    CheckForMissingEntriesCallbackHandler callbackHandler = new CheckForMissingEntriesCallbackHandler();
    mongoTemplate.executeQuery(query, GRIDFS_FILES_COLLECTION, callbackHandler);

    return callbackHandler.getFilesWithMissingEntry().stream().map(fileStorageService::findById).collect(toSet());
  }


  private void deleteObsoleteRPMs(String targetRepo, String sourceRepo) {
    Set<YumPackageReducedView> obsoleteRPMs = filterRPMsFromPropagationChain(obsoleteRpmFiler, targetRepo, sourceRepo);
    for (YumPackageReducedView obsoletePackage : obsoleteRPMs) {
      String[] strings = obsoletePackage.getLocation().getHref().split("/");
      FileDescriptor descriptor = new FileDescriptor(sourceRepo, strings[0], strings[1]);
      LOGGER.info("delete obsolete RPM {} obsoleted by a RPM in {}", descriptor.getPath(), targetRepo);

      try {
        storageService.delete(descriptor);
      } catch (RuntimeException e) {
        LOGGER.warn("oops", e);
      }
    }
  }


  private Set<YumPackageReducedView> filterRPMsFromPropagationChain(Filter filter, String targetRepo,
                                                                    String sourceRepo) {
    Map<String, Map<String, YumPackage>> newestTargetPackages = findNewestPackages(
      yumEntriesRepository.findByRepo(
        targetRepo));
    List<YumEntry> sourceRepoEntries = yumEntriesRepository.findByRepo(sourceRepo);
    return filterRPMs(filter, newestTargetPackages,
      sourceRepoEntries);
  }


  private Set<YumPackageReducedView> filterRPMs(Filter filter,
                                                Map<String, Map<String, YumPackage>> newestTargetPackagesByNameAndArch,
                                                List<YumEntry> sourceRepoEntries) {
    Set<YumPackageReducedView> result = new TreeSet<>();
    for (YumEntry entry : sourceRepoEntries) {
      YumPackage yumPackage = entry.getYumPackage();
      YumPackage newestPackageInTargetRepo = getMatchingYumPackageByNameAndArchIfAny(newestTargetPackagesByNameAndArch,
        yumPackage);
      if (filter.select(newestPackageInTargetRepo, yumPackage)) {
        LOGGER.info("found a {} version of {}", filter.getFilterDescription(), yumPackage.getName());
        result.add(new YumPackageReducedView(yumPackage));
      }
    }
    return result;
  }


  private YumPackage getMatchingYumPackageByNameAndArchIfAny(Map<String, Map<String, YumPackage>> packagesByNameAndArch,
                                                             YumPackage yumPackage) {
    Map<String, YumPackage> rpmsByArch = packagesByNameAndArch.get(yumPackage.getName());
    if (rpmsByArch != null) {
      return rpmsByArch.get(yumPackage.getArch());
    }
    return null;
  }

  /**
  * determine newest RPMs by name and architecture
  * @param inputList list of yum entries in repo
  * @return a map of maps, first map key is rpm name, second maps key is arch
  */
  private Map<String, Map<String, YumPackage>> findNewestPackages(List<YumEntry> inputList) {
    Map<String, Map<String, YumPackage>> result = new HashMap<>();
    for (YumEntry entry : inputList) {
      YumPackage yumPackage = entry.getYumPackage();

      Map<String, YumPackage> packageMap = result.get(yumPackage.getName());
      YumPackage packageForArchInMap = null;
      if (packageMap == null) {
        packageMap = new HashMap<>();
        result.put(yumPackage.getName(), packageMap);
      } else {
        packageForArchInMap = packageMap.get(yumPackage.getArch());
      }
      if ((packageForArchInMap == null) ||
          (versionComparator.compare(yumPackage.getVersion(), packageForArchInMap.getVersion()) > 0)) {
        packageMap.put(yumPackage.getArch(), yumPackage);
      }
    }
    return result;
  }

  @MongoTx
  public Map<ObjectId, YumPackageReducedView> deleteYumEntriesWithoutAssociatedFiles() {
    Map<ObjectId, YumPackageReducedView> result = getYumEntriesWithoutAssociatedFiles();
    result.keySet().forEach(yumEntriesRepository::delete);
    return result;
  }

  private interface Filter {
    boolean select(YumPackage newestTargetPackage, YumPackage sourcePackage);

    String getFilterDescription();
  }

  private class ObsoleteRpmFilter implements Filter {
    @Override
    public boolean select(YumPackage newestTargetPackage, YumPackage sourcePackage) {
      return (newestTargetPackage != null) &&
        (versionComparator.compare(newestTargetPackage.getVersion(), sourcePackage.getVersion()) > 0);
    }

    @Override
    public String getFilterDescription() {
      return "is obsolete";
    }
  }

  private class PropagatableRpmFilter implements Filter {
    @Override
    public boolean select(YumPackage newestTargetPackage, YumPackage sourcePackage) {
      return (newestTargetPackage == null) ||
        (versionComparator.compare(newestTargetPackage.getVersion(), sourcePackage.getVersion()) < 0);
    }

    @Override
    public String getFilterDescription() {
      return "is propagatable";
    }
  }

  private class DeleteObsoleteRPMsJob implements Runnable {
    private final String sourceRepo;
    private final String propagationTargetRepo;

    public DeleteObsoleteRPMsJob(String sourceRepo,
                                 String propagationTargetRepo) {
      this.sourceRepo = sourceRepo;
      this.propagationTargetRepo = propagationTargetRepo;
    }

    @Override
    public void run() {
      new MDCHelper(this.getClass()).run(() -> {
        deleteObsoleteRPMs(propagationTargetRepo, sourceRepo);
        LOGGER.info("finished deleting Obsolete RPMs without Exception");
      });
    }
  }


  private class CheckForMissingFsFilesCallbackHandler implements DocumentCallbackHandler {
    private List<ObjectId> entriesWithMissingFile = new ArrayList<>();

    @Override
    public void processDocument(DBObject dbObject) {
      ObjectId id = getId(dbObject);

      if (gridFsTemplate.findOne(Query.query(where("_id").is(id))) == null) {
        entriesWithMissingFile.add(id);
      }
    }

    private ObjectId getId(DBObject dbObject) {
      return (ObjectId) dbObject.get("_id");
    }

    private List<ObjectId> getEntriesWithMissingFile() {
      return entriesWithMissingFile;
    }
  }

  private class CheckForMissingEntriesCallbackHandler implements DocumentCallbackHandler {
    private List<ObjectId> filesWithMissingEntry = new ArrayList<>();

    @Override
    public void processDocument(DBObject dbObject) {
      ObjectId id = getId(dbObject);

      if (yumEntriesRepository.findOne(id) == null) {
        filesWithMissingEntry.add(id);
      }
    }

    private ObjectId getId(DBObject dbObject) {
      return (ObjectId) dbObject.get("_id");
    }

    private List<ObjectId> getFilesWithMissingEntry() {
      return filesWithMissingEntry;
    }
  }
}
