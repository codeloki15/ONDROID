package com.locallink.pro.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        sessionDao = db.sessionDao()
        messageDao = db.messageDao()
    }

    @After fun teardown() = db.close()

    @Test fun insertAndObserveMessages() = runBlocking {
        val s = SessionEntity(id = "s1", title = "T", createdAt = 1, updatedAt = 1)
        sessionDao.upsert(s)
        messageDao.insert(MessageEntity(id = "m1", sessionId = "s1", role = "user", text = "hi", timestamp = 2))
        val msgs = messageDao.observeMessages("s1").first()
        assertEquals(1, msgs.size)
        assertEquals("hi", msgs[0].text)
    }

    @Test fun cascadeDeleteRemovesMessages() = runBlocking {
        val s = SessionEntity(id = "s1", title = "T", createdAt = 1, updatedAt = 1)
        sessionDao.upsert(s)
        messageDao.insert(MessageEntity(id = "m1", sessionId = "s1", role = "user", text = "hi", timestamp = 2))
        sessionDao.delete(s)
        assertEquals(0, messageDao.getMessages("s1").size)
    }
}
