package engineering.b67.intellij_reek_plugin;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.VirtualFile;
import engineering.b67.intellij_linter_base.*;
import engineering.b67.intellij_linter_base.exception.ContextException;
import engineering.b67.intellij_linter_base.exception.SdkException;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.ruby.gem.RubyGemExecutionContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.IOException;

public class ReekExecutorRunner extends ExecutorRunner implements Runner {

    @Override
    public List<Warning> execute(@NotNull ExecutorContext executorContext) throws ContextException, SdkException {
        Editor editor = executorContext.getEditor();
        VirtualFile file = createVirtualFile(editor.getDocument(), getFileExtension());
        Project project = editor.getProject();
        ReekService state = ReekService.getInstance(project);

        Executor executor = createExecutor(
                getCommandContext(state, executorContext),
                file,
                project.getBasePath(),
                state
        );

        try {
            // Log file path and size
            Path filePath = Paths.get(file.getPath());
            long fileSize = Files.size(filePath);
            log.info("File path: " + filePath.toString());
            log.info("File size: " + fileSize + " bytes");

            // Log Reek parameters
            ArrayList<String> parameters = getParameters(state);
            log.info("Reek parameters: " + String.join(" ", parameters));

            Process process = executor.run();
            String output = getOutput(process);

            if (output.equals("")) {
                log.error("Empty output for file: " + filePath.toString());
                log.error("File size: " + fileSize + " bytes");
                log.error("Reek parameters: " + String.join(" ", parameters));
            }

            Result result = new ReekResult(output);

            return result.getWarnings();
        } catch (IOException e) {
            log.error("Failed to retrieve file details or execute Reek process.", e);
            throw new ContextException("Execution failed.");
        } finally {
            this.deleteVirtualFile(file);
        }
    }

    @Override
    public ArrayList<String> getParameters(Service state) {
        ArrayList<String> parameters = new ArrayList<String>() {
            {
                add("--single-line");
                add("--no-progress");
                add("--no-empty-headings");
                add("--format");
                add("json");
            }
        };

        if (!state.getConfig().equals("")) {
            parameters.add("--config");
            parameters.add(state.getConfig());
        }

        return parameters;
    }

    public CommandContext getCommandContext(ReekService state, ExecutorContext executorContext) throws ContextException, SdkException {
        if (StringUtils.isEmpty(state.getExecutable())) {
            return getDefaultCommandContext(executorContext);
        }

        return new CommandContext(state.getExecutable(), null);
    }

    @Override
    public CommandContext getDefaultCommandContext(ExecutorContext executorContext) throws ContextException, SdkException {
        Sdk sdk = executorContext.getSdk();
        if (sdk == null) {
            throw new SdkException("SDK is not available in current context");
        }

        String executable = RubyGemExecutionContext.getScriptPath(sdk, executorContext.getModule(), "reek");

        if (executable == null) {
            log.error("Failed to obtain the Reek executable path using SDK: " + sdk.getName());
            // Consider fetching a generic path or using another method to obtain the executable if possible
            // Example: Check for a system-wide installation or a predefined path
            throw new ContextException("Failed to find the Reek executable.");
        }

        String interpreter = sdk.getHomePath();
        if (interpreter == null) {
            log.error("Home path for the SDK is not defined.");
            throw new ContextException("Interpreter home path is undefined.");
        }

        return new CommandContext(executable, interpreter);
    }

    @Override
    public String getFileExtension() {
        return "rb";
    }

    @Override
    public String getOutput(Process process) {
        Reader inputStreamReader = new InputStreamReader(process.getInputStream());
        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

        return bufferedReader.lines().collect(Collectors.joining());
    }
}
