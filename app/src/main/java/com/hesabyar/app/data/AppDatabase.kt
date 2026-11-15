package com.hesabyar.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amount: Long,
    val isIncome: Boolean,
    val category: String,
    val dateMillis: Long
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val code: String = "",
    val price: Long,
    val stock: Int
)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY dateMillis DESC, id DESC")
    fun getAll(): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(item: TransactionEntity)

    @Delete
    suspend fun delete(item: TransactionEntity)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE ASC")
    fun getAll(): Flow<List<ProductEntity>>

    @Insert
    suspend fun insert(item: ProductEntity)

    @Update
    suspend fun update(item: ProductEntity)

    @Delete
    suspend fun delete(item: ProductEntity)

    // کم کردن موجودی فقط اگر موجودی کافی باشد. تعداد ردیف‌های تغییر‌یافته را برمی‌گرداند
    @Query("UPDATE products SET stock = stock - :qty WHERE id = :id AND stock >= :qty")
    suspend fun decreaseStock(id: Long, qty: Int): Int
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `products` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`code` TEXT NOT NULL, " +
                "`price` INTEGER NOT NULL, " +
                "`stock` INTEGER NOT NULL)"
        )
    }
}

@Database(
    entities = [TransactionEntity::class, ProductEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hesabyar.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
