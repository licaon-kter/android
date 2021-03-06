/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter.journalmanager;

import com.etesync.syncadapter.App;
import com.etesync.syncadapter.HttpClient;
import com.etesync.syncadapter.model.CollectionInfo;

import org.apache.commons.codec.Charsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ServiceTest {
    private OkHttpClient httpClient;
    private HttpUrl remote;
    private String authToken;

    @Before
    public void setUp() throws Exception {
        httpClient = HttpClient.create(null);
        remote = HttpUrl.parse("http://localhost:8000"); // FIXME: hardcode for now, should make configureable
        JournalAuthenticator journalAuthenticator = new JournalAuthenticator(httpClient, remote);
        authToken = journalAuthenticator.getAuthToken(Helpers.USER, Helpers.PASSWORD);

        httpClient = HttpClient.create(null, App.log, null, authToken);

        /* Reset */
        Request request = new Request.Builder()
                .post(new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return null;
                    }

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {

                    }
                })
                .url(remote.newBuilder().addEncodedPathSegments("reset/").build())
                .build();
        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            throw new Exception("Failed resetting");
        }
    }

    @After
    public void tearDown() throws IOException {
    }

    @Test
    public void testSyncSimple() throws IOException, Exceptions.HttpException, Exceptions.GenericCryptoException, Exceptions.IntegrityException {
        Exception caught;
        JournalManager journalManager = new JournalManager(httpClient, remote);
        CollectionInfo info = CollectionInfo.defaultForServiceType(CollectionInfo.Type.ADDRESS_BOOK);
        info.uid = JournalManager.Journal.genUid();
        info.displayName = "Test";
        Crypto.CryptoManager crypto = new Crypto.CryptoManager(info.version, Helpers.keyBase64, info.uid);
        JournalManager.Journal journal = new JournalManager.Journal(crypto, info.toJson(), info.uid);
        journalManager.create(journal);

        // Try pushing the same journal (uid clash)
        try {
            caught = null;
            journalManager.create(journal);
        } catch (Exceptions.HttpException e) {
            caught = e;
        }
        assertNotNull(caught);

        List<JournalManager.Journal> journals = journalManager.list();
        assertEquals(journals.size(), 1);
        CollectionInfo info2 = CollectionInfo.fromJson(journals.get(0).getContent(crypto));
        assertEquals(info2.displayName, info.displayName);

        // Update journal
        info.displayName = "Test 2";
        journal = new JournalManager.Journal(crypto, info.toJson(), info.uid);
        journalManager.update(journal);

        journals = journalManager.list();
        assertEquals(journals.size(), 1);
        info2 = CollectionInfo.fromJson(journals.get(0).getContent(crypto));
        assertEquals(info2.displayName, info.displayName);

        // Delete journal
        journalManager.delete(journal);

        journals = journalManager.list();
        assertEquals(journals.size(), 0);

        // Bad HMAC
        info.uid = JournalManager.Journal.genUid();
        journal = new JournalManager.Journal(crypto, info.toJson(), info.uid);
        info.displayName = "Test 3";
        //// We assume this doesn't update the hmac.
        journal.setContent(crypto, info.toJson());
        journalManager.create(journal);

        try {
            caught = null;
            for (JournalManager.Journal journal1 : journalManager.list()) {
                Crypto.CryptoManager crypto1 = new Crypto.CryptoManager(info.version, Helpers.keyBase64, journal1.getUid());
                journal1.verify(crypto1);
            }
        } catch (Exceptions.IntegrityException e) {
            caught = e;
        }
        assertNotNull(caught);
    }


    @Test
    public void testSyncEntry() throws IOException, Exceptions.HttpException, Exceptions.GenericCryptoException, Exceptions.IntegrityException {
        Exception caught;
        JournalManager journalManager = new JournalManager(httpClient, remote);
        CollectionInfo info = CollectionInfo.defaultForServiceType(CollectionInfo.Type.ADDRESS_BOOK);
        info.uid = JournalManager.Journal.genUid();
        info.displayName = "Test";
        Crypto.CryptoManager crypto = new Crypto.CryptoManager(info.version, Helpers.keyBase64, info.uid);
        JournalManager.Journal journal = new JournalManager.Journal(crypto, info.toJson(), info.uid);
        journalManager.create(journal);

        JournalEntryManager journalEntryManager = new JournalEntryManager(httpClient, remote, info.uid);
        JournalEntryManager.Entry previousEntry = null;
        JournalEntryManager.Entry entry = new JournalEntryManager.Entry();
        entry.update(crypto, "Content", previousEntry);

        List<JournalEntryManager.Entry> entries = new LinkedList<>();

        entries.add(entry);
        journalEntryManager.create(entries, null);
        previousEntry = entry;

        entries.clear();
        JournalEntryManager.Entry entry2 = new JournalEntryManager.Entry();
        entry2.update(crypto, "Content", previousEntry);
        entries.add(entry2);

        // Pushing a correct entries without the last parameter
        try {
            caught = null;
            journalEntryManager.create(entries, null);
        } catch (Exceptions.HttpException e) {
            caught = e;
        }
        assertNotNull(caught);

        // Adding a second entry
        journalEntryManager.create(entries, previousEntry.getUid());
        previousEntry = entry2;

        entries.clear();
        entries.add(entry);
        entries.add(entry2);

        // Check last works:
        entries = journalEntryManager.list(crypto, entry.getUid(), 0);
        assertEquals(entries.size(), 1);
        entries = journalEntryManager.list(crypto, entry2.getUid(), 0);
        assertEquals(entries.size(), 0);

        // Corrupt the journal and verify we catch it
        entries.clear();
        entry2 = new JournalEntryManager.Entry();
        entry2.update(crypto, "Content", null);
        entries.add(entry2);

        journalEntryManager.create(entries, previousEntry.getUid());

        try {
            caught = null;
            journalEntryManager.list(crypto, null, 0);
        } catch (Exceptions.IntegrityException e) {
            caught = e;
        }
        assertNotNull(caught);
    }


    @Test
    public void testUserInfo() throws IOException, Exceptions.HttpException, Exceptions.GenericCryptoException, Exceptions.IntegrityException {
        Crypto.CryptoManager cryptoManager = new Crypto.CryptoManager(Constants.CURRENT_VERSION, Helpers.keyBase64, "userInfo");
        UserInfoManager.UserInfo userInfo, userInfo2;
        UserInfoManager manager = new UserInfoManager(httpClient, remote);

        // Get when there's nothing
        userInfo = manager.get(Helpers.USER);
        assertNull(userInfo);

        // Create
        userInfo = UserInfoManager.UserInfo.generate(cryptoManager, Helpers.USER);
        manager.create(userInfo);

        // Get
        userInfo2 = manager.get(Helpers.USER);
        assertNotNull(userInfo2);
        assertArrayEquals(userInfo.getContent(cryptoManager), userInfo2.getContent(cryptoManager));

        // Update
        userInfo.setContent(cryptoManager, "test".getBytes(Charsets.UTF_8));
        manager.update(userInfo);
        userInfo2 = manager.get(Helpers.USER);
        assertNotNull(userInfo2);
        assertArrayEquals(userInfo.getContent(cryptoManager), userInfo2.getContent(cryptoManager));

        // Delete
        manager.delete(userInfo);
        userInfo = manager.get(Helpers.USER);
        assertNull(userInfo);
    }


    @Test
    public void testJournalMember() throws IOException, Exceptions.HttpException, Exceptions.GenericCryptoException, Exceptions.IntegrityException {
        Exception caught;
        JournalManager journalManager = new JournalManager(httpClient, remote);
        CollectionInfo info = CollectionInfo.defaultForServiceType(CollectionInfo.Type.ADDRESS_BOOK);
        info.uid = JournalManager.Journal.genUid();
        info.displayName = "Test";
        Crypto.CryptoManager crypto = new Crypto.CryptoManager(info.version, Helpers.keyBase64, info.uid);
        JournalManager.Journal journal = new JournalManager.Journal(crypto, info.toJson(), info.uid);
        journalManager.create(journal);

        assertEquals(journalManager.listMembers(journal).size(), 0);

        // Test inviting ourselves
        JournalManager.Member member = new JournalManager.Member(Helpers.USER, "test".getBytes(Charsets.UTF_8));
        try {
            caught = null;
            journalManager.addMember(journal, member);
        } catch (Exceptions.HttpException e) {
            caught = e;
        }
        assertNotNull(caught);

        JournalManager.Member member2 = new JournalManager.Member(Helpers.USER2, "test".getBytes(Charsets.UTF_8));
        journalManager.addMember(journal, member2);
        assertEquals(journalManager.listMembers(journal).size(), 1);

        // Uninviting user
        journalManager.deleteMember(journal, member2);

        assertEquals(journalManager.listMembers(journal).size(), 0);
    }
}
