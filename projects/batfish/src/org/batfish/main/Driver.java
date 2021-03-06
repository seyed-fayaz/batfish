package org.batfish.main;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.batfish.common.BfConsts;
import org.batfish.common.CoordConsts;
import org.batfish.common.BfConsts.TaskStatus;
import org.codehaus.jettison.json.JSONArray;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.jettison.JettisonFeature;
import org.glassfish.jersey.server.ResourceConfig;

public class Driver {

   private static boolean _idle = true;

   private static BatfishLogger _mainLogger = null;

   private static Settings _mainSettings = null;

   private static HashMap<String, Task> _taskLog;

   private static void applyAutoBaseDir(final Settings settings) {
      String baseDir = settings.getAutoBaseDir();
      if (baseDir != null) {
         settings.setSerializeIndependentPath(Paths.get(baseDir,
               BfConsts.RELPATH_VENDOR_INDEPENDENT_CONFIG_DIR).toString());
         settings.setSerializeVendorPath(Paths.get(baseDir,
               BfConsts.RELPATH_VENDOR_SPECIFIC_CONFIG_DIR).toString());
         settings.setTestRigPath(Paths.get(baseDir,
               BfConsts.RELPATH_TEST_RIG_DIR).toString());
         settings.setServiceLogicBloxHostname(_mainSettings
               .getServiceLogicBloxHostname());
         settings.setLogicDir(_mainSettings.getLogicDir());
         String envName = settings.getEnvironmentName();
         if (envName != null) {
            Path envPath = Paths.get(baseDir,
                  BfConsts.RELPATH_ENVIRONMENTS_DIR, envName);
            settings.setDumpFactsDir(envPath.resolve(
                  BfConsts.RELPATH_FACT_DUMP_DIR).toString());
            settings.setDataPlaneDir(envPath.resolve(
                  BfConsts.RELPATH_DATA_PLANE_DIR).toString());
            settings.setJobLogicBloxHostnamePath(envPath.resolve(
                  BfConsts.RELPATH_LB_HOSTNAME_PATH).toString());
            String workspaceBasename = Paths.get(baseDir).getFileName()
                  .toString();
            String workspaceName = workspaceBasename + ":" + envName;
            settings.setWorkspaceName(workspaceName);
            settings.setNodeSetPath(envPath.resolve(
                  BfConsts.RELPATH_ENV_NODE_SET).toString());
            settings.setZ3DataPlaneFile(envPath.resolve(
                  BfConsts.RELPATH_Z3_DATA_PLANE_FILE).toString());
            settings.setQueryDumpDir(envPath.resolve(
                  BfConsts.RELPATH_QUERY_DUMP_DIR).toString());
         }
         String questionName = settings.getQuestionName();
         if (questionName != null) {
            Path questionPath = Paths.get(baseDir,
                  BfConsts.RELPATH_QUESTIONS_DIR, questionName);
            settings.setQuestionPath(questionPath.resolve(
                  BfConsts.RELPATH_QUESTION_FILE).toString());
            settings.setTrafficFactDumpDir(questionPath.resolve(
                  BfConsts.RELPATH_FLOWS_DUMP_DIR).toString());
         }
      }
   }

   private static synchronized boolean claimIdle() {
      if (_idle) {
         _idle = false;
         return true;
      }

      return false;
   }

   public static boolean getIdle() {
      return _idle;
   }

   public static BatfishLogger getMainLogger() {
      return _mainLogger;
   }

   synchronized static Task getTaskkFromLog(String taskId) {
      if (_taskLog.containsKey(taskId)) {
         return _taskLog.get(taskId);
      }
      else {
         return null;
      }
   }

   private synchronized static void logTask(String taskId, Task task)
         throws Exception {
      if (_taskLog.containsKey(taskId)) {
         throw new Exception("duplicate UUID for task");
      }
      else {
         _taskLog.put(taskId, task);
      }
   }

   public static void main(String[] args) {
      _taskLog = new HashMap<String, Task>();

      try {
         _mainSettings = new Settings(args);
      }
      catch (ParseException e) {
         System.err.println("batfish: Parsing command-line failed. Reason: "
               + e.getMessage());
         System.exit(1);
      }
      _mainLogger = new BatfishLogger(_mainSettings);
      System.setErr(_mainLogger.getPrintStream());
      System.setOut(_mainLogger.getPrintStream());
      _mainSettings.setLogger(_mainLogger);
      if (_mainSettings.runInServiceMode()) {
         URI baseUri = UriBuilder.fromUri(_mainSettings.getServiceUrl())
               .port(_mainSettings.getServicePort()).build();

         _mainLogger.output(String.format("Starting server at %s\n", baseUri));

         ResourceConfig rc = new ResourceConfig(Service.class)
               .register(new JettisonFeature());

         GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);

         try {
            if (_mainSettings.getCoordinatorHost() != null) {
               boolean registrationSuccess;
               do {
                  registrationSuccess = registerWithCoordinator();
                  if (!registrationSuccess) {
                     ;
                     _mainLogger
                           .error("Unable to register  with coordinator\n");
                     Thread.sleep(1000); // 1 second
                  }
               } while (!registrationSuccess);
            }

            // sleep indefinitely, in 10 minute chunks
            while (true) {
               Thread.sleep(10 * 60 * 1000); // 10 minutes
            }
         }
         catch (Exception ex) {
            String stackTrace = ExceptionUtils.getFullStackTrace(ex);
            _mainLogger.error(stackTrace);
         }
      }
      else if (_mainSettings.canExecute()) {
         _mainSettings.setLogger(_mainLogger);
         applyAutoBaseDir(_mainSettings);
         if (!RunBatfish(_mainSettings)) {
            System.exit(1);
         }
      }
   }

   private static void makeIdle() {
      _idle = true;
   }

   private static boolean registerWithCoordinator() {
      String coordinatorHost = _mainSettings.getCoordinatorHost();
      String workMgr = coordinatorHost + ":"
            + _mainSettings.getCoordinatorWorkPort();
      String poolMgr = coordinatorHost + ":"
            + _mainSettings.getCoordinatorPoolPort();
      try {
         Client client = ClientBuilder.newClient();
         WebTarget webTarget = client.target(
               String.format("http://%s%s/%s", poolMgr,
                     CoordConsts.SVC_BASE_POOL_MGR,
                     CoordConsts.SVC_POOL_UPDATE_RSC)).queryParam(
               "add",
               _mainSettings.getServiceHost() + ":"
                     + _mainSettings.getServicePort());
         Response response = webTarget.request(MediaType.APPLICATION_JSON)
               .get();

         _mainLogger.output(response.getStatus() + " "
               + response.getStatusInfo() + " " + response + "\n");

         if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            _mainLogger.error("Did not get an OK response\n");
            return false;
         }

         String sobj = response.readEntity(String.class);
         JSONArray array = new JSONArray(sobj);
         _mainLogger.outputf("response: %s [%s] [%s]\n", array.toString(),
               array.get(0), array.get(1));

         if (!array.get(0).equals(CoordConsts.SVC_SUCCESS_KEY)) {
            _mainLogger.errorf("got error while checking work status: %s %s\n",
                  array.get(0), array.get(1));
            return false;
         }

         return true;
      }
      catch (ProcessingException e) {
         _mainLogger.errorf("unable to connect to %s: %s\n", workMgr,
               ExceptionUtils.getStackTrace(e));
         return false;
      }
      catch (Exception e) {
         _mainLogger.errorf("exception: " + ExceptionUtils.getStackTrace(e));
         return false;
      }
   }

   private static boolean RunBatfish(Settings settings) {
      BatfishLogger logger = settings.getLogger();
      boolean noError = true;
      try (Batfish batfish = new Batfish(settings)) {
         batfish.run();
      }
      catch (Exception e) {
         String stackTrace = ExceptionUtils.getFullStackTrace(e);
         logger.error(stackTrace);
         noError = false;
      }

      return noError;
   }

   public static List<String> RunBatfishThroughService(String taskId,
         String[] args) {
      final Settings settings;
      try {
         settings = new Settings(args);
      }
      catch (ParseException e) {
         return Arrays.asList("failure",
               "Parsing command-line failed: " + e.getMessage());
      }

      applyAutoBaseDir(settings);

      if (settings.canExecute()) {
         if (claimIdle()) {

            // lets put a try-catch around all the code around claimIdle
            // so that we never the worker non-idle accidentally

            try {

               final BatfishLogger jobLogger = new BatfishLogger(settings);
               settings.setLogger(jobLogger);

               final Task task = new Task(args);

               logTask(taskId, task);

               // run batfish on a new thread and set idle to true when done
               Thread thread = new Thread() {
                  @Override
                  public void run() {
                     task.setStatus(TaskStatus.InProgress);
                     if (RunBatfish(settings)) {
                        task.setStatus(TaskStatus.TerminatedNormally);
                     }
                     else {
                        task.setStatus(TaskStatus.TerminatedAbnormally);
                     }
                     task.setTerminated();
                     jobLogger.close();
                     makeIdle();
                  }
               };

               thread.start();

               return Arrays.asList(BfConsts.SVC_SUCCESS_KEY, "running now");
            }
            catch (Exception e) {
               _mainLogger.error("Exception while running task: "
                     + e.getMessage());
               makeIdle();
               return Arrays.asList(BfConsts.SVC_FAILURE_KEY, e.getMessage());
            }
         }
         else {
            return Arrays.asList(BfConsts.SVC_FAILURE_KEY, "Not idle");
         }
      }
      else {
         return Arrays.asList(BfConsts.SVC_FAILURE_KEY,
               "Non-executable command");
      }
   }

}
