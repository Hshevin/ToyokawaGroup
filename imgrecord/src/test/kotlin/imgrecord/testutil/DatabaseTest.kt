package imgrecord.testutil

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import imgrecord.db.ImageRecordDao
import imgrecord.db.ImageRecordDatabase
import org.junit.After
import org.junit.Before
import org.robolectric.annotation.Config

@Config(sdk = [28])
abstract class DatabaseTest {

    protected lateinit var dao: ImageRecordDao
    private lateinit var database: ImageRecordDatabase

    @Before
    fun setUpDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            ImageRecordDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.imageRecordDao()
    }

    @After
    fun tearDownDatabase() {
        database.close()
    }
}
