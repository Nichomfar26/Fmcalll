package com.fmcall.serval.di

import android.content.Context
import androidx.room.Room
import com.fmcall.serval.data.FMcallDatabase
import com.fmcall.serval.mesh.PacketRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): FMcallDatabase =
        Room.databaseBuilder(ctx, FMcallDatabase::class.java, "fmcall_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideMeshNodeDao(db: FMcallDatabase) = db.meshNodeDao()
    @Provides fun provideContactDao(db: FMcallDatabase)  = db.contactDao()
    @Provides fun provideMessageDao(db: FMcallDatabase)  = db.messageDao()
    @Provides fun provideConvDao(db: FMcallDatabase)     = db.conversationDao()
    @Provides fun provideCallLogDao(db: FMcallDatabase)  = db.callLogDao()

    @Provides @Singleton
    fun providePacketRouter(): PacketRouter = PacketRouter()
}
