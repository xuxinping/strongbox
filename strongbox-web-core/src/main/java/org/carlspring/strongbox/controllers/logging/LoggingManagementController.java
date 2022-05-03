package org.carlspring.strongbox.controllers.logging;

import org.carlspring.strongbox.booters.PropertiesBooter;
import org.carlspring.strongbox.controllers.BaseController;
import org.carlspring.strongbox.domain.DirectoryListing;
import org.carlspring.strongbox.services.DirectoryListingService;
import org.carlspring.strongbox.services.DirectoryListingServiceImpl;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.logging.LogFileWebEndpointProperties;
import org.springframework.boot.actuate.logging.LogFileWebEndpoint;
import org.springframework.boot.logging.LogFile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.carlspring.strongbox.controllers.logging.LoggingManagementController.ROOT_CONTEXT;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * This controllers provides a simple wrapper over REST API for the LoggingManagementService.
 *
 * @author Martin Todorov
 * @author Pablo Tirado
 * @author Aditya Srinivasan
 * @author Przemyslaw Fusik
 */
@Controller
@Api(value = ROOT_CONTEXT)
@RequestMapping(ROOT_CONTEXT)
@PreAuthorize("hasAnyAuthority('ADMIN')")
public class LoggingManagementController
        extends BaseController
{

    public final static String ROOT_CONTEXT = "/api/logging";

    @Value("${strongbox.sse.timeoutMillis:600000}")
    private Long sseTimeoutMillis;

    @Inject
    private PropertiesBooter propertiesBooter;

    @Inject
    private Environment environment;

    @Inject
    private Optional<LogFileWebEndpointProperties> logFileWebEndpointProperties;

    @Inject
    private Function<SseEmitter, SseEmitterAwareTailerListenerAdapter> tailerListenerAdapterPrototypeFactory;

    private DirectoryListingService directoryListingService;

    public DirectoryListingService getDirectoryListingService()
    {
        return Optional.ofNullable(directoryListingService).orElseGet(() -> {
            String baseUrl = StringUtils.chomp(configurationManager.getConfiguration().getBaseUrl(), "/");

            return directoryListingService = new DirectoryListingServiceImpl(
                    String.format("%s" + ROOT_CONTEXT, baseUrl));
        });
    }

    @ApiOperation(value = "Used to download log data.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "The log file was retrieved successfully."),
                            @ApiResponse(code = 400, message = "Could not download log data.") })
    @GetMapping(value = "/download/{path:.+}",
                produces = { MediaType.APPLICATION_OCTET_STREAM_VALUE, // forces browser to actually download the file
                             MediaType.TEXT_PLAIN_VALUE,               // plain text / json upon errors
                             MediaType.APPLICATION_JSON_VALUE })
    public ResponseEntity downloadLog(@PathVariable("path") String path,
                                      @RequestHeader(HttpHeaders.ACCEPT) String accept)
    {
        try
        {
            Path logsBaseDir = Paths.get(propertiesBooter.getLogsDirectory());
            Path requestedLogPath = Paths.get(logsBaseDir.toString(), path);

            logger.debug(String.format("Requested downloading log from path: [%s] resolved to [%s]",
                                       path,
                                       requestedLogPath));

            if (!Files.exists(requestedLogPath))
            {
                return getNotFoundResponseEntity("Requested path does not exist!", accept);
            }
            if (Files.isDirectory(requestedLogPath))
            {
                return getBadRequestResponseEntity("Requested path is a directory!", accept);
            }

            return getStreamToResponseEntity(logFileInputStream(requestedLogPath), FilenameUtils.getName(path));
        }
        catch (IOException e)
        {
            String message = "Could not download log data.";

            return getExceptionResponseEntity(BAD_REQUEST, message, e, accept);
        }
    }

    @ApiOperation(value = "Used to get logs directory.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "The logs directory was retrieved successfully."),
                            @ApiResponse(code = 500, message = "Server error.") })
    @GetMapping(value = { "/browse/{path:.+}" },
                produces = { MediaType.TEXT_PLAIN_VALUE,
                             MediaType.TEXT_HTML_VALUE,
                             MediaType.APPLICATION_JSON_VALUE })
    public Object browseLogsDirectory(@PathVariable("path") Optional<String> path,
                                      ModelMap model,
                                      HttpServletRequest request,
                                      @RequestHeader(value = HttpHeaders.ACCEPT,
                                                     required = false) String acceptHeader)
    {
        logger.debug("Requested directory listing of logs {}/logs/{}", ROOT_CONTEXT, path.orElse(""));

        try
        {
            Path logsBaseDir = Paths.get(propertiesBooter.getLogsDirectory());
            Path requestedLogPath = Paths.get(logsBaseDir.toString(), path.orElse(""));

            logger.debug("Requested directory listing of path: [{}] resolved to [{}]", path, requestedLogPath);

            if (!Files.exists(requestedLogPath))
            {
                return getNotFoundResponseEntity("Requested path does not exist!", acceptHeader);
            }
            if (!Files.isDirectory(requestedLogPath))
            {
                return getBadRequestResponseEntity("Requested path is not a directory!", acceptHeader);
            }

            DirectoryListing directoryListing = getDirectoryListingService().fromPath(logsBaseDir, requestedLogPath);

            if (acceptHeader != null && acceptHeader.contains(MediaType.APPLICATION_JSON_VALUE))
            {
                return ResponseEntity.ok(objectMapper.writer().writeValueAsString(directoryListing));
            }

            String currentUrl = StringUtils.chomp(request.getRequestURI(), "/");
            String downloadUrl = currentUrl.replaceFirst("/browse", "/download");
            boolean showBack = path.isPresent() && !StringUtils.isBlank(path.get());

            model.addAttribute("showBack", showBack);
            model.addAttribute("currentUrl", currentUrl);
            model.addAttribute("downloadBaseUrl", downloadUrl);
            model.addAttribute("directories", directoryListing.getDirectories());
            model.addAttribute("files", directoryListing.getFiles());

            return new ModelAndView("directoryListing", model);
        }
        catch (Exception e)
        {
            String message = "Attempt to browse logs failed. Check server logs for more information.";
            return getExceptionResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR, message, e, acceptHeader);
        }
    }

    @ApiOperation(value = "Used to stream logging file.")
    @GetMapping(value = "/stream", produces = { MediaType.TEXT_EVENT_STREAM_VALUE,
                                                  org.carlspring.strongbox.net.MediaType.TEXT_PLAIN_UTF8_VALUE })
    public SseEmitter logFileStream()
            throws IOException
    {
        final SseEmitter sseEmitter = new SseEmitter(sseTimeoutMillis);
        Resource logFileResource = getLogFileResource();
        if (logFileResource == null)
        {
            sseEmitter.completeWithError(new IllegalStateException("Missing '" +LogFile.FILE_NAME_PROPERTY+ "' or '" + LogFile.FILE_PATH_PROPERTY + "' properties"));
            return sseEmitter;
        }

        ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();
        forkJoinPool.execute(
                Tailer.create(logFileResource.getFile(),
                              tailerListenerAdapterPrototypeFactory.apply(sseEmitter),
                              1000,
                              true));

        return sseEmitter;
    }

    /**
     * @see LogFileWebEndpoint#logFile()
     */
    private Resource getLogFileResource()
    {
        if (logFileWebEndpointProperties.isPresent() && logFileWebEndpointProperties.get().getExternalFile() != null)
        {
            return new FileSystemResource(logFileWebEndpointProperties.get().getExternalFile());
        }

        LogFile logFile = LogFile.get(environment);

        if (logFile == null)
        {
            logger.debug("Missing '" +LogFile.FILE_NAME_PROPERTY+ "' or '" + LogFile.FILE_PATH_PROPERTY + "' properties");
            return null;
        }

        return new FileSystemResource(logFile.toString());
    }

    private InputStream logFileInputStream(Path logFilePath)
            throws IOException
    {
        return new BufferedInputStream(Files.newInputStream(logFilePath));
    }

}
