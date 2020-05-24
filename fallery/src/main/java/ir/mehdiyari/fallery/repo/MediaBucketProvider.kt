package ir.mehdiyari.fallery.repo

import android.annotation.SuppressLint
import android.content.Context
import android.provider.MediaStore
import ir.mehdiyari.fallery.models.BucketType
import ir.mehdiyari.fallery.models.MediaBucket
import ir.mehdiyari.fallery.utils.*
import ir.mehdiyari.fallery.utils.getSingleBucketSelection
import ir.mehdiyari.fallery.utils.videoPhotoBucketSelection
import java.io.File
import java.util.*

internal class MediaBucketProvider constructor(
    private val context: Context
) : AbstractMediaBucketProvider {

    /**
     * get all buckets(folders) that contain video or photo or both based on [bucketType]
     */
    override suspend fun getMediaBuckets(bucketType: BucketType): List<MediaBucket> =
        mutableListOf<MediaBucket>().let { bucketList ->
            // store media dates if [bucketType] == [BucketType.VIDEO_PHOTO_BUCKETS]
            val dateAddedList = mutableListOf<Pair<String, Long>>()

            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"), bucketProjection, getQueryByMediaType(bucketType), getQueryArgByMediaType(bucketType), "date_added DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndex("bucket_id"))
                    getFirstMediaThumbForBuckets(
                        bucketType, id, cursor.getString(cursor.getColumnIndex(bucketProjection[3]))
                    ).also { firstMediaThumb ->
                        if (firstMediaThumb.second != 0L) dateAddedList.add(firstMediaThumb.first.path to firstMediaThumb.second)

                        bucketList.add(
                            MediaBucket(
                                id = cursor.getLong(cursor.getColumnIndex("bucket_id")), bucketPath = firstMediaThumb.first.path.replace(firstMediaThumb.first.name, ""), displayName = cursor.getString(cursor.getColumnIndex(bucketProjection[2])), firstMediaThumbPath = firstMediaThumb.first.path, mediaCount = cursor.getInt(cursor.getColumnIndex("count"))
                            )
                        )
                    }
                }
            }

            if (bucketType == BucketType.VIDEO_PHOTO_BUCKETS && dateAddedList.isNotEmpty()) {
                sortBucketList(dateAddedList.toList(), bucketList)
            } else bucketList
        }.toList().addAllMediaModel()


    /**
     * if bucket list is not empty add AllMedia to index 0 of bucket list
     */
    private fun List<MediaBucket>.addAllMediaModel(): List<MediaBucket> = this.toMutableList().apply {
            if (this.isNotEmpty()) {
                add(0, MediaBucket(UUID.randomUUID().mostSignificantBits, "", "All Media", this.first().firstMediaThumbPath, this.sumBy { it.mediaCount }))
            }
        }


    private fun sortBucketList(
        dateAddedList: List<Pair<String, Long>>, bucketList: MutableList<MediaBucket>
    ): MutableList<MediaBucket> {
        val newBucketList = mutableListOf<MediaBucket>()
        dateAddedList.sortedByDescending { it.second }.forEach {
            bucketList.firstOrNull { currentBucket -> currentBucket.firstMediaThumbPath == it.first }
                .also { bucket ->
                    if (bucket != null) {
                        newBucketList.add(bucket)
                        bucketList.remove(bucket)
                    }
                }
        }

        newBucketList.addAll(bucketList)
        bucketList.clear()
        return newBucketList
    }

    /**
     * get first media sort desc by dateAdd from [getFirstMediaFromBucketByMediaType]
     * and if video create thumb for it else return just file
     */
    @SuppressLint("DefaultLocale")
    private fun getFirstMediaThumbForBuckets(
        bucketType: BucketType, id: Long, path: String
    ): Pair<File, Long> = when (bucketType) {
        BucketType.ONLY_PHOTO_BUCKETS -> File(path) to 0L
        BucketType.ONLY_VIDEO_BUCKETS -> File(createThumbForVideos(listOf(path to id), context).first()) to 0L
        else -> {
            getFirstMediaFromBucketByMediaType(id, bucketType).let { pair ->
                val file = File(pair.first)
                if (VideoMediaTypes.values().map { type -> type.value.second }
                        .firstOrNull { it.contains(file.extension.toLowerCase()) } != null) File(
                    createThumbForVideos(listOf(file.path to id), context)
                        .first()) to pair.second
                else file to pair.second
            }
        }
    }

    /**
     * search from (photo-video, both) in bucket and take latest media added to this bucket with date of added as Long
     */
    private fun getFirstMediaFromBucketByMediaType(
        bucketId: Long, bucketType: BucketType
    ): Pair<String, Long> {
        val query = when (bucketType) {
            BucketType.VIDEO_PHOTO_BUCKETS -> Pair(
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)", arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            )
            BucketType.ONLY_VIDEO_BUCKETS -> Pair(
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?", arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            )
            BucketType.ONLY_PHOTO_BUCKETS -> Pair(
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?", arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            )
        }

        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED), """${query.first} AND bucket_id=?""", arrayOf(*query.second, bucketId.toString()), "date_added DESC"
        )?.use { cursor ->
            val path = if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DATA)) to cursor.getLong(
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                )
            } else Pair("", 0L)
            cursor.close()
            return path
        }

        return Pair("", 0L)
    }

    private fun getQueryByMediaType(mediaType: BucketType): String = when (mediaType) {
        BucketType.VIDEO_PHOTO_BUCKETS -> videoPhotoBucketSelection
        else -> getSingleBucketSelection
    }

    private fun getQueryArgByMediaType(mediaType: BucketType): Array<String> = when (mediaType) {
        BucketType.VIDEO_PHOTO_BUCKETS -> arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        BucketType.ONLY_PHOTO_BUCKETS -> arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
        BucketType.ONLY_VIDEO_BUCKETS -> arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
    }
}