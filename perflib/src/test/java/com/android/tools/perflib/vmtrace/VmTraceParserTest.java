/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.perflib.vmtrace;

import com.android.annotations.NonNull;
import com.android.utils.SparseArray;
import com.google.common.primitives.Ints;

import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class VmTraceParserTest extends TestCase {
    public void testParseHeader() throws IOException {
        File f = getFile("/header.trace");
        VmTraceParser parser = new VmTraceParser(f);
        parser.parseHeader(f);
        VmTraceData traceData = parser.getTraceData();

        assertEquals(3, traceData.getVersion());
        assertTrue(traceData.isDataFileOverflow());
        assertEquals(VmTraceData.VmClockType.DUAL, traceData.getVmClockType());
        assertEquals("dalvik", traceData.getVm());

        Collection<ThreadInfo> threads = traceData.getThreads();
        assertEquals(2, threads.size());
        assertEquals(1, traceData.getThread("main").getId());
        assertEquals(11, traceData.getThread("AsyncTask #1").getId());

        Map<Long, MethodInfo> methods = traceData.getMethods();
        assertEquals(4, methods.size());

        MethodInfo info = traceData.getMethod(0x62830738);
        assertNotNull(info);
        assertEquals("android/graphics/Bitmap", info.className);
        assertEquals("access$100", info.methodName);
        assertEquals("(I)V", info.signature);
        assertEquals("android/graphics/BitmapF.java", info.srcPath);
        assertEquals(29, info.srcLineNumber);

        info = traceData.getMethod(0x6282b4b0);
        assertNotNull(info);
        assertEquals(-1, info.srcLineNumber);
    }

    private class CallFormatter implements Call.Formatter {
        private final Map<Long, MethodInfo> mMethodInfo;

        public CallFormatter(Map<Long, MethodInfo> methodInfo) {
            mMethodInfo = methodInfo;
        }

        @Override
        public String format(Call c) {
            MethodInfo info = mMethodInfo.get(c.getMethodId());
            return info == null ? Long.toString(c.getMethodId()) : info.getFullName();
        }
    }

    private void testTrace(String traceName, String threadName, String expectedCallSequence)
            throws IOException {
        VmTraceData traceData = getVmTraceData(traceName);

        ThreadInfo thread = traceData.getThread(threadName);
        assertNotNull(String.format("Thread %s was not found in the trace", threadName), thread);

        Call call = thread.getTopLevelCall();
        assertNotNull(call);
        String actual = call.format(new CallFormatter(traceData.getMethods()));
        assertEquals(expectedCallSequence, actual);
    }

    public void testBasicTrace() throws IOException {
        String expected =
                          " -> AsyncTask #1.:  -> android/os/Debug.startMethodTracing: (Ljava/lang/String;)V -> android/os/Debug.startMethodTracing: (Ljava/lang/String;II)V -> dalvik/system/VMDebug.startMethodTracing: (Ljava/lang/String;II)V\n"
                        + "                    -> com/test/android/traceview/Basic.foo: ()V -> com/test/android/traceview/Basic.bar: ()I\n"
                        + "                    -> android/os/Debug.stopMethodTracing: ()V -> dalvik/system/VMDebug.stopMethodTracing: ()V";
        testTrace("/basic.trace", "AsyncTask #1", expected);
    }

    public void testMisMatchedTrace() throws IOException {
        String expected =
                  " -> AsyncTask #1.:  -> com/test/android/traceview/MisMatched.foo: ()V -> com/test/android/traceview/MisMatched.bar: ()V -> android/os/Debug.startMethodTracing: (Ljava/lang/String;)V -> android/os/Debug.startMethodTracing: (Ljava/lang/String;II)V -> dalvik/system/VMDebug.startMethodTracing: (Ljava/lang/String;II)V\n"
                + "                                                                                                                        -> com/test/android/traceview/MisMatched.baz: ()I\n"
                + "                    -> android/os/Debug.stopMethodTracing: ()V -> dalvik/system/VMDebug.stopMethodTracing: ()V";
        testTrace("/mismatched.trace", "AsyncTask #1", expected);
    }

    public void testExceptionTrace() throws IOException {
        String expected =
                  " -> AsyncTask #1.:  -> android/os/Debug.startMethodTracing: (Ljava/lang/String;)V -> android/os/Debug.startMethodTracing: (Ljava/lang/String;II)V -> dalvik/system/VMDebug.startMethodTracing: (Ljava/lang/String;II)V\n"
                + "                    -> com/test/android/traceview/Exceptions.foo: ()V -> com/test/android/traceview/Exceptions.bar: ()V -> com/test/android/traceview/Exceptions.baz: ()V -> java/lang/RuntimeException.<init>: ()V -> java/lang/Exception.<init>: ()V -> java/lang/Throwable.<init>: ()V -> java/util/Collections.emptyList: ()Ljava/util/List;\n"
                + "                                                                                                                                                                                                                                                                                          -> java/lang/Throwable.fillInStackTrace: ()Ljava/lang/Throwable; -> java/lang/Throwable.nativeFillInStackTrace: ()Ljava/lang/Object;\n"
                + "                    -> android/os/Debug.stopMethodTracing: ()V -> dalvik/system/VMDebug.stopMethodTracing: ()V";
        testTrace("/exception.trace", "AsyncTask #1", expected);
    }

    public void testCallDurations() throws IOException {
        validateCallDurations("/basic.trace", "AsyncTask #1");
        validateCallDurations("/mismatched.trace", "AsyncTask #1");
        validateCallDurations("/exception.trace", "AsyncTask #1");
    }

    private void validateCallDurations(String traceName, String threadName) throws IOException {
        VmTraceData traceData = getVmTraceData(traceName);

        ThreadInfo thread = traceData.getThread(threadName);
        assertNotNull(String.format("Thread %s was not found in the trace", threadName), thread);

        Call topLevelCall = thread.getTopLevelCall();
        assertNotNull(topLevelCall);
        Iterator<Call> it = topLevelCall.getCallHierarchyIterator();
        while (it.hasNext()) {
            Call c = it.next();

            assertTrue(c.getEntryTime(ClockType.GLOBAL, TimeUnit.NANOSECONDS) <=
                    c.getExitTime(ClockType.GLOBAL, TimeUnit.NANOSECONDS));
            assertTrue(c.getEntryTime(ClockType.THREAD, TimeUnit.NANOSECONDS) <=
                    c.getExitTime(ClockType.THREAD, TimeUnit.NANOSECONDS));
        }
    }

    public void testMethodStats() throws IOException {
        VmTraceData traceData = getVmTraceData("/basic.trace");
        final String threadName = "AsyncTask #1";
        List<Map.Entry<Long, MethodInfo>> methods = new ArrayList<Map.Entry<Long, MethodInfo>>(
                traceData.getMethods().entrySet());
        Collections.sort(methods, new Comparator<Map.Entry<Long, MethodInfo>>() {
            @Override
            public int compare(Map.Entry<Long, MethodInfo> o1, Map.Entry<Long, MethodInfo> o2) {
                long diff = o2.getValue().getInclusiveTime(threadName, ClockType.THREAD) -
                        o1.getValue().getInclusiveTime(threadName, ClockType.THREAD);
                return Ints.saturatedCast(diff);
            }
        });

        // verify that the top level actually comes out with the max time
        // note that while this works for the simple traces currently being tested, this
        // condition itself isn't valid in case some methods are being called from multiple
        // threads, in which their inclusive time could be higher than any of the thread's
        // toplevel time.
        assertEquals("AsyncTask #1.: ", methods.get(0).getValue().getFullName());
    }

    // Validate that the exclusive time of the top level call = sum of all inclusive times of
    // all methods called from that top level
    public void testMethodStats2() throws IOException {
        VmTraceData traceData = getVmTraceData("/basic.trace");
        String thread = "AsyncTask #1";
        Call top = traceData.getThread(thread).getTopLevelCall();

        assertNotNull(top);

        long topThreadTime = top.getInclusiveTime(ClockType.THREAD, TimeUnit.NANOSECONDS);

        Collection<MethodInfo> methods = traceData.getMethods().values();
        Iterator<MethodInfo> it = methods.iterator();
        long sum = 0;

        while (it.hasNext()) {
            MethodInfo method = it.next();
            sum += method.getExclusiveTime(thread, ClockType.THREAD);
        }

        assertEquals(topThreadTime, sum);
    }

    public void testSearch() throws IOException {
        VmTraceData traceData = getVmTraceData("/basic.trace");
        String thread = "AsyncTask #1";

        SearchResult results = traceData.searchFor("startMethodTracing", thread);

        // 3 different methods (varying in parameter list) of name startMethodTracing are called
        assertEquals(3, results.getMethods().size());
        assertEquals(3, results.getInstances().size());
    }

    // Validates that search is not impacted by current locale
    public void testSearchLocale() throws IOException {
        VmTraceData traceData = getVmTraceData("/basic.trace");
        String thread = "AsyncTask #1";

        String pattern = "ii)v";
        SearchResult results = traceData.searchFor(pattern, thread);

        Locale originalDefaultLocale = Locale.getDefault();

        try {
            // Turkish has two different variants for lowercase i
            Locale.setDefault(new Locale("tr", "TR"));
            SearchResult turkish = traceData.searchFor(pattern, thread);

            assertEquals(results.getInstances().size(), turkish.getInstances().size());
            assertEquals(results.getMethods().size(), turkish.getMethods().size());
        } finally {
            Locale.setDefault(originalDefaultLocale);
        }

    }

    private VmTraceData getVmTraceData(String traceFilePath) throws IOException {
        VmTraceParser parser = new VmTraceParser(getFile(traceFilePath));
        parser.parse();
        return parser.getTraceData();
    }

    private File getFile(String path) {
        URL resource = getClass().getResource(path);
        // Note: When running from an IntelliJ, make sure the IntelliJ compiler settings treats
        // *.trace files as resources, otherwise they are excluded from compiler output
        // resulting in a NPE.
        assertNotNull(path + " not found", resource);
        return new File(resource.getFile());
    }
}
