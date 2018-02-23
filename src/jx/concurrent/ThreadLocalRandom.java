/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package jx.concurrent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sun.misc.VM;


/**
 * A random number generator isolated to the current thread. Like the
 * global {@link java.util.Random} generator used by the {@link
 * java.lang.Math} class, a {@code ThreadLocalRandom} is initialized
 * with an internally generated seed that may not otherwise be
 * modified. When applicable, use of {@code ThreadLocalRandom} rather
 * than shared {@code Random} objects in concurrent programs will
 * typically encounter much less overhead and contention. Use of
 * {@code ThreadLocalRandom} is particularly appropriate when multiple
 * tasks (for example, each a {@link ForkJoinTask}) use random numbers
 * in parallel in thread pools.
 *
 * <p>Usages of this class should typically be of the form:
 * {@code ThreadLocalRandom.current().nextX(...)} (where
 * {@code X} is {@code Int}, {@code Long}, etc).
 * When all usages are of this form, it is never possible to
 * accidently share a {@code ThreadLocalRandom} across multiple threads.
 *
 * <p>This class also provides additional commonly used bounded random
 * generation methods.
 *
 * <p>Instances of {@code ThreadLocalRandom} are not cryptographically
 * secure. Consider instead using {@link java.security.SecureRandom}
 * in security-sensitive applications. Additionally,
 * default-constructed instances do not use a cryptographically random
 * seed unless the {@linkplain System#getProperty system property}
 * {@code java.util.secureRandomSeed} is set to {@code true}.
 *
 * @since 1.7
 * @author Doug Lea
 */
final class ThreadLocalRandom {
  /*
   * This class implements the java.util.Random API (and subclasses
   * Random) using a single static instance that accesses random
   * number state held in class Thread (primarily, field
   * threadLocalRandomSeed). In doing so, it also provides a home
   * for managing package-private utilities that rely on exactly the
   * same state as needed to maintain the ThreadLocalRandom
   * instances. We leverage the need for an initialization flag
   * field to also use it as a "probe" -- a self-adjusting thread
   * hash used for contention avoidance, as well as a secondary
   * simpler (xorShift) random seed that is conservatively used to
   * avoid otherwise surprising users by hijacking the
   * ThreadLocalRandom sequence. The dual use is a marriage of
   * convenience, but is a simple and efficient way of reducing
   * application-level overhead and footprint of most concurrent
   * programs.
   *
   * Even though this class subclasses java.util.Random, it uses the
   * same basic algorithm as java.util.SplittableRandom. (See its
   * internal documentation for explanations, which are not repeated
   * here.) Because ThreadLocalRandoms are not splittable
   * though, we use only a single 64bit gamma.
   *
   * Because this class is in a different package than class Thread,
   * field access methods use Unsafe to bypass access control rules.
   * To conform to the requirements of the Random superclass
   * constructor, the common static ThreadLocalRandom maintains an
   * "initialized" field for the sake of rejecting user calls to
   * setSeed while still allowing a call from constructor. Note
   * that serialization is completely unnecessary because there is
   * only a static singleton. But we generate a serial form
   * containing "rnd" and "initialized" fields to ensure
   * compatibility across versions.
   *
   * Implementations of non-core methods are mostly the same as in
   * SplittableRandom, that were in part derived from a previous
   * version of this class.
   *
   * The nextLocalGaussian ThreadLocal supports the very rarely used
   * nextGaussian method by providing a holder for the second of a
   * pair of them. As is true for the base class version of this
   * method, this time/space tradeoff is probably never worthwhile,
   * but we provide identical statistical properties.
   */

  /** Generates per-thread initialization/probe field. */
  private static final AtomicInteger probeGenerator =
      new AtomicInteger();

  /**
   * The next seed for default constructors.
   */
  private static final AtomicLong seeder = new AtomicLong(initialSeed());

  private static long initialSeed() {
    String sec = VM.getSavedProperty("java.util.secureRandomSeed");
    if (Boolean.parseBoolean(sec)) {
      byte[] seedBytes = java.security.SecureRandom.getSeed(8);
      long s = (seedBytes[0]) & 0xffL;
      for (int i = 1; i < 8; ++i) {
        s = (s << 8) | ((seedBytes[i]) & 0xffL);
      }
      return s;
    }
    return (mix64(System.currentTimeMillis()) ^
        mix64(System.nanoTime()));
  }

  /**
   * The increment for generating probe values.
   */
  private static final int PROBE_INCREMENT = 0x9e3779b9;

  /**
   * The increment of seeder per new instance.
   */
  private static final long SEEDER_INCREMENT = 0xbb67ae8584caa73bL;

  private static long mix64(long z) {
    z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
    z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
    return z ^ (z >>> 33);
  }

  /**
   * Initialize Thread fields for the current thread. Called only
   * when Thread.threadLocalRandomProbe is zero, indicating that a
   * thread local seed value needs to be generated. Note that even
   * though the initialization is purely thread-local, we need to
   * rely on (static) atomic generators to initialize the values.
   */
  static void localInit() {
    int p = probeGenerator.addAndGet(PROBE_INCREMENT);
    int probe = (p == 0) ? 1 : p; // skip 0
    long seed = mix64(seeder.getAndAdd(SEEDER_INCREMENT));
    Thread t = Thread.currentThread();
    UNSAFE.putLong(t, SEED, seed);
    UNSAFE.putInt(t, PROBE, probe);
  }

  // Within-package utilities

  /*
   * Descriptions of the usages of the methods below can be found in
   * the classes that use them. Briefly, a thread's "probe" value is
   * a non-zero hash code that (probably) does not collide with
   * other existing threads with respect to any power of two
   * collision space. When it does collide, it is pseudo-randomly
   * adjusted (using a Marsaglia XorShift). The nextSecondarySeed
   * method is used in the same contexts as ThreadLocalRandom, but
   * only for transient usages such as random adaptive spin/block
   * sequences for which a cheap RNG suffices and for which it could
   * in principle disrupt user-visible statistical properties of the
   * main ThreadLocalRandom if we were to use it.
   *
   * Note: Because of package-protection issues, versions of some
   * these methods also appear in some subpackage classes.
   */

  /**
   * Returns the probe value for the current thread without forcing
   * initialization. Note that invoking ThreadLocalRandom.current()
   * can be used to force initialization on zero return.
   */
  static int getProbe() {
    return UNSAFE.getInt(Thread.currentThread(), PROBE);
  }

  /**
   * Pseudo-randomly advances and records the given probe value for the
   * given thread.
   */
  static int advanceProbe(int probe) {
    probe ^= probe << 13; // xorshift
    probe ^= probe >>> 17;
    probe ^= probe << 5;
    UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
    return probe;
  }

  /**
   * Returns the pseudo-randomly initialized or updated secondary seed.
   */
  static int nextSecondarySeed() {
    int r;
    Thread t = Thread.currentThread();
    if ((r = UNSAFE.getInt(t, SECONDARY)) != 0) {
      r ^= r << 13; // xorshift
      r ^= r >>> 17;
      r ^= r << 5;
    } else {
      localInit();
      if ((r = (int) UNSAFE.getLong(t, SEED)) == 0) {
        r = 1; // avoid zero
      }
    }
    UNSAFE.putInt(t, SECONDARY, r);
    return r;
  }

  // Unsafe mechanics
  private static final sun.misc.Unsafe UNSAFE;
  private static final long            SEED;
  private static final long            PROBE;
  private static final long            SECONDARY;

  static {
    try {
      UNSAFE = sun.misc.Unsafe.getUnsafe();
      Class<?> tk = Thread.class;
      SEED = UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomSeed"));
      PROBE = UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomProbe"));
      SECONDARY =
          UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomSecondarySeed"));
    } catch (Exception e) {
      throw new Error(e);
    }
  }
}
