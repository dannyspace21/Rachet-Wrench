// DatabaseUpgradeTest.kt — updating the app must never lose data.
//
// Builds a REAL version-1 database (exactly the tables the previous app
// versions created, from app/schemas/.../1.json) with a vehicle in it, then
// opens it with the current app.  The vehicle must still be there and the
// new shopping list table must work.
package com.ratchetwrench.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ratchetwrench.app.data.AppDatabase
import com.ratchetwrench.app.data.ShoppingItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
class DatabaseUpgradeTest {
    @Test fun version1DatabaseUpgradesWithoutLosingData() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        AppDatabase.resetForTests()
        val file = ctx.getDatabasePath("ratchet-wrench.db")
        file.parentFile?.mkdirs(); file.delete()

        // The version-1 database, exactly as Room created it.
        val schema = Json.parseToJsonElement(File("schemas/com.ratchetwrench.app.data.AppDatabase/1.json").readText())
            .jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
                old.execSQL(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach { i ->
                    old.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("""INSERT INTO vehicles (id, kind, year, make, model, `trim`, nickname, vin, current_mileage, mileage_unit,
                photo_attachment_id, parts_url, owners_url, notes, created_at, updated_at, deleted_at, dirty)
                VALUES ('v1', 'car', 2015, 'Lexus', 'RC 350', '', 'Daily', '', 42000, 'mi', NULL, '', '', '', 'x', 'x', NULL, 0)""")
            old.version = 1
        }

        val db = AppDatabase.get(ctx)
        runBlocking {
            assertEquals("Lexus", db.vehicles().get("v1")?.make)
            db.shopping().upsert(ShoppingItem(id = "s1", vehicle_id = "v1", description = "Oil filter", quantity = 2))
            assertEquals(2, db.shopping().get("s1")?.quantity)
        }
        AppDatabase.resetForTests()
    }
}
