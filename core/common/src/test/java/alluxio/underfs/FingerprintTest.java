/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.underfs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import alluxio.security.authorization.AccessControlList;
import alluxio.util.CommonUtils;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * Tests for the {@link Fingerprint} class.
 */
public final class FingerprintTest {

  private Random mRandom = new Random();

  @Test
  public void parseFileFingerprint() {
    UfsStatus status = new UfsFileStatus(CommonUtils.randomAlphaNumString(10),
        CommonUtils.randomAlphaNumString(10), mRandom.nextLong(), mRandom.nextLong(),
        CommonUtils.randomAlphaNumString(10), CommonUtils.randomAlphaNumString(10),
        (short) mRandom.nextInt(), mRandom.nextLong());
    Fingerprint fp = Fingerprint.create(CommonUtils.randomAlphaNumString(10), status);
    String expected = fp.serialize();
    assertNotNull(expected);
    assertEquals(expected, Fingerprint.parse(expected).serialize());
  }

  @Test
  public void parseDirectoryFingerprint() {
    UfsStatus status = new UfsDirectoryStatus(CommonUtils.randomAlphaNumString(10),
        CommonUtils.randomAlphaNumString(10), CommonUtils.randomAlphaNumString(10),
        (short) mRandom.nextInt(), mRandom.nextLong());
    Fingerprint fp = Fingerprint.create(CommonUtils.randomAlphaNumString(10), status);
    String expected = fp.serialize();
    assertNotNull(expected);
    assertEquals(expected, Fingerprint.parse(expected).serialize());
  }

  @Test
  public void parseInvalidFingerprint() {
    Fingerprint fp = Fingerprint.create(CommonUtils.randomAlphaNumString(10), null);
    String expected = fp.serialize();
    assertNotNull(expected);
    assertEquals(expected, Fingerprint.parse(expected).serialize());
  }

  @Test
  public void matchMetadataOrContent() {
    String name = CommonUtils.randomAlphaNumString(10);
    String contentHash = CommonUtils.randomAlphaNumString(10);
    String contentHash2 = CommonUtils.randomAlphaNumString(11);
    Long contentLength = mRandom.nextLong();
    Long lastModifiedTimeMs = mRandom.nextLong();
    String owner = CommonUtils.randomAlphaNumString(10);
    String group = CommonUtils.randomAlphaNumString(10);
    short mode = (short) mRandom.nextInt();
    String ufsName = CommonUtils.randomAlphaNumString(10);
    Long blockSize = mRandom.nextLong();

    UfsFileStatus status = new UfsFileStatus(name, contentHash, contentLength, lastModifiedTimeMs,
        owner, group, mode, blockSize);
    UfsFileStatus metadataChangedStatus = new UfsFileStatus(name, contentHash, contentLength,
        lastModifiedTimeMs, CommonUtils.randomAlphaNumString(10),
        CommonUtils.randomAlphaNumString(10), mode, blockSize);
    UfsFileStatus dataChangedStatus = new UfsFileStatus(name, contentHash2, contentLength,
        lastModifiedTimeMs, owner, group, mode, blockSize);
    Fingerprint fp = Fingerprint.create(ufsName, status);
    Fingerprint fpMetadataChanged = Fingerprint.create(ufsName, metadataChangedStatus);
    Fingerprint fpDataChanged = Fingerprint.create(ufsName, dataChangedStatus);

    assertTrue(fp.matchMetadata(fp));
    assertFalse(fp.matchMetadata(fpMetadataChanged));
    assertTrue(fp.matchContent(fpMetadataChanged));
    assertFalse(fp.matchContent(fpDataChanged));
    assertTrue(fp.matchMetadata(fpDataChanged));
  }

  @Test
  public void createFingerprintFromUfsStatus() {
    String name = CommonUtils.randomAlphaNumString(10);
    String owner = CommonUtils.randomAlphaNumString(10);
    String group = CommonUtils.randomAlphaNumString(10);
    short mode = (short) mRandom.nextInt();
    String ufsName = CommonUtils.randomAlphaNumString(10);

    UfsDirectoryStatus dirStatus = new UfsDirectoryStatus(name, owner, group, mode);
    Fingerprint fp = Fingerprint.create(ufsName, dirStatus);
    assertEquals(owner, fp.getTag(Fingerprint.Tag.OWNER));
    assertEquals(group, fp.getTag(Fingerprint.Tag.GROUP));
    assertEquals(String.valueOf(mode), fp.getTag(Fingerprint.Tag.MODE));

    String contentHash = CommonUtils.randomAlphaNumString(10);
    Long contentLength = mRandom.nextLong();
    Long lastModifiedTimeMs = mRandom.nextLong();
    Long blockSize = mRandom.nextLong();

    UfsFileStatus fileStatus = new UfsFileStatus(name, contentHash, contentLength,
        lastModifiedTimeMs, owner, group, mode, blockSize);
    fp = Fingerprint.create(ufsName, fileStatus);

    assertEquals(owner, fp.getTag(Fingerprint.Tag.OWNER));
    assertEquals(group, fp.getTag(Fingerprint.Tag.GROUP));
    assertEquals(String.valueOf(mode), fp.getTag(Fingerprint.Tag.MODE));
    assertEquals(contentHash, fp.getTag(Fingerprint.Tag.CONTENT_HASH));

    // create a fingerprint with a custom content hash
    String contentHash2 = CommonUtils.randomAlphaNumString(10);
    fp = Fingerprint.create(ufsName, fileStatus, contentHash2);
    assertEquals(contentHash2, fp.getTag(Fingerprint.Tag.CONTENT_HASH));
  }

  @Test
  public void createACLFingeprint() {
    UfsStatus status = new UfsFileStatus(CommonUtils.randomAlphaNumString(10),
        CommonUtils.randomAlphaNumString(10), mRandom.nextLong(), mRandom.nextLong(),
        CommonUtils.randomAlphaNumString(10), CommonUtils.randomAlphaNumString(10),
        (short) mRandom.nextInt(), mRandom.nextLong());
    AccessControlList acl = AccessControlList.fromStringEntries(
        CommonUtils.randomAlphaNumString(10),
        CommonUtils.randomAlphaNumString(10),
        Arrays.asList("user::rw-", "group::r--", "other::rwx"));
    Fingerprint fp = Fingerprint.create(CommonUtils.randomAlphaNumString(10), status, null, acl);
    String expected = fp.serialize();
    assertNotNull(expected);
    assertEquals("user::rw-,group::r--,other::rwx",
        Fingerprint.parse(expected).getTag(Fingerprint.Tag.ACL));
    assertEquals(expected, Fingerprint.parse(expected).serialize());
  }

  @Test
  public void sanitizeString() {
    Fingerprint dummy = Fingerprint.INVALID_FINGERPRINT;
    assertEquals("foobar", dummy.sanitizeString("foobar"));
    assertEquals("foo_bar", dummy.sanitizeString("foo bar"));
    assertEquals("foo_bar", dummy.sanitizeString("foo|bar"));
    assertEquals("foo_bar_baz", dummy.sanitizeString("foo bar|baz"));
    assertEquals("foo_bar_baz_qux", dummy.sanitizeString("foo bar baz qux"));
  }
}
