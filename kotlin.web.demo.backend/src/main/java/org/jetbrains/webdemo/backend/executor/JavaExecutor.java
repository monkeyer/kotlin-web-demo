/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.webdemo.backend.executor;

import org.jetbrains.webdemo.ErrorWriter;
import org.jetbrains.webdemo.JsonUtils;
import org.jetbrains.webdemo.ResponseUtils;
import org.jetbrains.webdemo.backend.BackendSettings;
import org.jetbrains.webdemo.backend.executor.result.JavaExecutionResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Timer;
import java.util.TimerTask;

public class JavaExecutor {
    private static Timer timer = new Timer(true);
    private static final String TIME_OUT_RESULT = JsonUtils.toJson(new JavaExecutionResult(BackendSettings.KOTLIN_TIMEOUT_MESSAGE, null));
    private static final String LONG_OUTPUT_RESULT = JsonUtils.toJson(new JavaExecutionResult(BackendSettings.KOTLIN_LONG_OUTPUT_MESSAGE, null));
    private String[] args;
    private volatile boolean isTimeout = false;
    private volatile boolean outputIsTooLong = false;

    JavaExecutor(String[] args) {
        this.args = args;
    }

    public ProgramOutput execute() throws Exception {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(args);
            process.getOutputStream().close();

            final StringBuilder errStream = new StringBuilder();
            final StringBuilder outStream = new StringBuilder();

            final Process finalProcess = process;

            final BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream()));
            final BufferedReader stdErr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            // Thread that reads std out and feeds the writer given in input
            final Thread stdReader = new Thread() {
                @Override
                public void run() {
                    String line;
                    try {
                        while (!Thread.interrupted() &&
                                (line = stdOut.readLine()) != null) {
                            outStream.append(ResponseUtils.escapeString(line));
                            if (outStream.length() > BackendSettings.MAX_OUTPUT_SIZE) {
                                outputIsTooLong = true;
                                finalProcess.destroy();
                                return;
                            }
                        }
                    } catch (Throwable e) {
                        if (!Thread.interrupted()) {
                            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, "");
                        }
                    }
                }
            };
            stdReader.start(); // Starts now

            // Thread that reads std err and feeds the writer given in input
            final Thread stdErrReader = new Thread() {
                @Override
                public void run() {
                    String line;
                    try {
                        while (!Thread.interrupted() &&
                                (line = stdErr.readLine()) != null) {
                            errStream.append(ResponseUtils.escapeString(line)).append(ResponseUtils.addNewLine());
                        }
                    } catch (Throwable e) {
                        if (!Thread.interrupted()) {
                            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, "");
                        }
                    }
                }
            };
            stdErrReader.start(); // Starts now

            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    isTimeout = true;
                    stdReader.interrupt();
                    stdErrReader.interrupt();
                    finalProcess.destroy();
                }
            }, BackendSettings.TIMEOUT_FOR_EXECUTION);

            int exitValue;
            try {
                exitValue = process.waitFor();
                stdReader.join(10000);
                stdErrReader.join(10000);
            } finally {
                try {
                    stdOut.close();
                    stdErr.close();
                } catch (IOException e) {
                    ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, "");
                }
            }

            if (outputIsTooLong) {
                return new ProgramOutput("", ResponseUtils.escapeString(LONG_OUTPUT_RESULT), true);
            } else if (isTimeout) {
                return new ProgramOutput("", ResponseUtils.escapeString(TIME_OUT_RESULT), true);
            } else {
                return new ProgramOutput(errStream.toString(), outStream.toString(), false);
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
