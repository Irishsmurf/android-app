package me.echeung.moemoekyun.adapter

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.databinding.DataBindingUtil
import me.echeung.moemoekyun.App
import me.echeung.moemoekyun.R
import me.echeung.moemoekyun.client.model.Song
import me.echeung.moemoekyun.databinding.SongDetailsBinding
import me.echeung.moemoekyun.util.SongActionsUtil

class SongDetailAdapter(private val activity: Activity, songs: List<Song>) : ArrayAdapter<Song>(activity, 0, songs) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(context)
        val binding: SongDetailsBinding
        var view = convertView

        if (view == null) {
            binding = DataBindingUtil.inflate(inflater, R.layout.song_details, parent, false)
            view = binding.root
            view.tag = binding
        } else {
            binding = view.tag as SongDetailsBinding
        }

        val song = getItem(position) ?: return binding.root

        binding.song = song
        binding.isAuthenticated = App.authUtil.isAuthenticated
        binding.canRequest = App.userViewModel!!.requestsRemaining != 0
        binding.isFavorite = song.isFavorite

        binding.requestBtn.setOnClickListener { _ -> SongActionsUtil.request(activity, song) }

        binding.favoriteBtn.setOnClickListener { _ ->
            SongActionsUtil.toggleFavorite(activity, song)

            song.isFavorite = !song.isFavorite
            binding.isFavorite = song.isFavorite
        }

        binding.root.setOnLongClickListener { v ->
            SongActionsUtil.copyToClipboard(v.context, song)
            true
        }

        return binding.root
    }

}