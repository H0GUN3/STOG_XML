package com.stog.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.stog.app.databinding.FragmentDiscoverBinding;

/** Feed of other travellers' posts. Sample content only until posts exist on the server. */
public final class DiscoverFragment extends Fragment {
    private FragmentDiscoverBinding binding;

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentDiscoverBinding.inflate(inflater, container, false);
        binding.discoverFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.discoverFeed.setAdapter(new FeedAdapter());
        return binding.getRoot();
    }

    @Override public void onDestroyView() {
        binding.discoverFeed.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    private static final class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_post, parent, false);
            view.findViewById(R.id.post_image).getLayoutParams().height = parent.getMeasuredWidth();
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int[] post = SamplePosts.ALL[position];
            View view = holder.itemView;
            ImageView image = view.findViewById(R.id.post_image);
            image.setImageResource(post[0]);
            String author = view.getContext().getString(post[1]);
            image.setContentDescription(view.getContext().getString(R.string.post_image_description, author));
            ((TextView) view.findViewById(R.id.post_author)).setText(author);
            ((TextView) view.findViewById(R.id.post_place)).setText(post[2]);
            ((TextView) view.findViewById(R.id.post_caption)).setText(post[3]);
        }

        @Override public int getItemCount() { return SamplePosts.ALL.length; }
    }
}
