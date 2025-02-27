/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.contentstack.okhttp.internal;

import com.contentstack.okhttp.internal.http.RetryableSink;
import com.contentstack.okhttp.internal.spdy.Header;
import com.contentstack.okio.Buffer;
import com.contentstack.okio.ByteString;
import com.contentstack.okio.Source;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/** Junk drawer of utility methods. */
public final class Util {
  public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  public static final String[] EMPTY_STRING_ARRAY = new String[0];

  /** A cheap and type-safe constant for the US-ASCII Charset. */
  public static final Charset US_ASCII = Charset.forName("US-ASCII");

  /** A cheap and type-safe constant for the UTF-8 Charset. */
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  private Util() {
  }

  public static int getEffectivePort(URI uri) {
    return getEffectivePort(uri.getScheme(), uri.getPort());
  }

  public static int getEffectivePort(URL url) {
    return getEffectivePort(url.getProtocol(), url.getPort());
  }

  private static int getEffectivePort(String scheme, int specifiedPort) {
    return specifiedPort != -1 ? specifiedPort : getDefaultPort(scheme);
  }

  public static int getDefaultPort(String protocol) {
    if ("http".equals(protocol)) return 80;
    if ("https".equals(protocol)) return 443;
    return -1;
  }

  public static void checkOffsetAndCount(long arrayLength, long offset, long count) {
    if ((offset | count) < 0 || offset > arrayLength || arrayLength - offset < count) {
      throw new ArrayIndexOutOfBoundsException();
    }
  }

  /** Returns true if two possibly-null objects are equal. */
  public static boolean equal(Object a, Object b) {
    return a == b || (a != null && a.equals(b));
  }

  /**
   * Closes {@code closeable}, ignoring any checked exceptions. Does nothing
   * if {@code closeable} is null.
   */
  public static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (RuntimeException rethrown) {
        throw rethrown;
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Closes {@code socket}, ignoring any checked exceptions. Does nothing if
   * {@code socket} is null.
   */
  public static void closeQuietly(Socket socket) {
    if (socket != null) {
      try {
        socket.close();
      } catch (RuntimeException rethrown) {
        throw rethrown;
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Closes {@code serverSocket}, ignoring any checked exceptions. Does nothing if
   * {@code serverSocket} is null.
   */
  public static void closeQuietly(ServerSocket serverSocket) {
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (RuntimeException rethrown) {
        throw rethrown;
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Closes {@code a} and {@code b}. If either close fails, this completes
   * the other close and rethrows the first encountered exception.
   */
  public static void closeAll(Closeable a, Closeable b) throws IOException {
    Throwable thrown = null;
    try {
      a.close();
    } catch (Throwable e) {
      thrown = e;
    }
    try {
      b.close();
    } catch (Throwable e) {
      if (thrown == null) thrown = e;
    }
    if (thrown == null) return;
    if (thrown instanceof IOException) throw (IOException) thrown;
    if (thrown instanceof RuntimeException) throw (RuntimeException) thrown;
    if (thrown instanceof Error) throw (Error) thrown;
    throw new AssertionError(thrown);
  }

  /**
   * Deletes the contents of {@code dir}. Throws an IOException if any file
   * could not be deleted, or if {@code dir} is not a readable directory.
   */
  public static void deleteContents(File dir) throws IOException {
    File[] files = dir.listFiles();
    if (files == null) {
      throw new IOException("not a readable directory: " + dir);
    }
    for (File file : files) {
      if (file.isDirectory()) {
        deleteContents(file);
      }
      if (!file.delete()) {
        throw new IOException("failed to delete file: " + file);
      }
    }
  }

  /** Reads until {@code in} is exhausted or the timeout has elapsed. */
  public static boolean skipAll(Source in, int timeoutMillis) throws IOException {
    long startNanos = System.nanoTime();
    Buffer skipBuffer = new Buffer();
    while (NANOSECONDS.toMillis(System.nanoTime() - startNanos) < timeoutMillis) {
      long read = in.read(skipBuffer, 2048);
      if (read == -1) return true; // Successfully exhausted the stream.
      skipBuffer.clear();
    }
    return false; // Ran out of time.
  }

  /** Returns a 32 character string containing a hash of {@code s}. */
  public static String hash(String s) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("MD5");
      byte[] md5bytes = messageDigest.digest(s.getBytes("UTF-8"));
      return ByteString.of(md5bytes).hex();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  /** Returns an immutable copy of {@code list}. */
  public static <T> List<T> immutableList(List<T> list) {
    return Collections.unmodifiableList(new ArrayList<T>(list));
  }

  /** Returns an immutable list containing {@code elements}. */
  public static <T> List<T> immutableList(T... elements) {
    return Collections.unmodifiableList(Arrays.asList(elements.clone()));
  }

  public static ThreadFactory threadFactory(final String name, final boolean daemon) {
    return new ThreadFactory() {
      @Override public Thread newThread(Runnable runnable) {
        Thread result = new Thread(runnable, name);
        result.setDaemon(daemon);
        return result;
      }
    };
  }

  public static List<Header> headerEntries(String... elements) {
    List<Header> result = new ArrayList<Header>(elements.length / 2);
    for (int i = 0; i < elements.length; i += 2) {
      result.add(new Header(elements[i], elements[i + 1]));
    }
    return result;
  }

  public static RetryableSink emptySink() {
    return EMPTY_SINK;
  }

  private static final RetryableSink EMPTY_SINK = new RetryableSink(0);
}
