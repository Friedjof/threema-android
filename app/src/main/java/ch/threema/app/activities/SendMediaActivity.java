/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2013-2022 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.activities;

import android.Manifest;
import android.animation.Animator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.adapters.SendMediaAdapter;
import ch.threema.app.camera.CameraActivity;
import ch.threema.app.camera.CameraUtil;
import ch.threema.app.camera.VideoEditView;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.emojis.EmojiButton;
import ch.threema.app.emojis.EmojiPicker;
import ch.threema.app.mediaattacher.MediaFilterQuery;
import ch.threema.app.mediaattacher.MediaSelectionActivity;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.services.DeadlineListService;
import ch.threema.app.services.FileService;
import ch.threema.app.services.MessageService;
import ch.threema.app.services.PreferenceService;
import ch.threema.app.ui.ComposeEditText;
import ch.threema.app.ui.DebouncedOnClickListener;
import ch.threema.app.ui.DebouncedOnMenuItemClickListener;
import ch.threema.app.ui.MediaItem;
import ch.threema.app.ui.SendButton;
import ch.threema.app.utils.AnimationUtil;
import ch.threema.app.utils.BitmapUtil;
import ch.threema.app.utils.BitmapWorkerTask;
import ch.threema.app.utils.BitmapWorkerTaskParams;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.EditTextUtil;
import ch.threema.app.utils.FileUtil;
import ch.threema.app.utils.IntentDataUtil;
import ch.threema.app.utils.MimeUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.app.video.VideoTimelineCache;
import ch.threema.base.ThreemaException;
import ch.threema.base.utils.LoggingUtil;
import pl.droidsonroids.gif.GifImageView;

import static ch.threema.app.ui.MediaItem.TYPE_GIF;
import static ch.threema.app.ui.MediaItem.TYPE_IMAGE;
import static ch.threema.app.ui.MediaItem.TYPE_IMAGE_CAM;
import static ch.threema.app.utils.BitmapUtil.FLIP_HORIZONTAL;
import static ch.threema.app.utils.BitmapUtil.FLIP_VERTICAL;

public class SendMediaActivity extends ThreemaToolbarActivity implements
	GenericAlertDialog.DialogClickListener,
	ThreemaToolbarActivity.OnSoftKeyboardChangedListener {

	private static final Logger logger = LoggingUtil.getThreemaLogger("SendMediaActivity");

	private static final String STATE_BIGIMAGE_POS = "bigimage_pos";
	private static final String STATE_ITEMS = "items";
	private static final String STATE_CROP_FILE = "cropfile";
	private static final String STATE_CAMERA_FILE = "cameraFile";
	private static final String STATE_VIDEO_FILE = "vidFile";

	public static final String EXTRA_URLILIST = "urilist";
	public static final String EXTRA_MEDIA_ITEMS = "mediaitems";
	public static final String EXTRA_USE_EXTERNAL_CAMERA = "extcam";

	public static final int MAX_EDITABLE_IMAGES = 256; // Max number of images/videos that can be edited here at once

	private static final String DIALOG_TAG_QUIT_CONFIRM = "qc";
	private static final long IMAGE_ANIMATION_DURATION_MS = 180;
	private static final int PERMISSION_REQUEST_CAMERA = 100;

	private SendMediaAdapter sendMediaAdapter;
	private RecyclerView recyclerView;
	private ImageView bigImageView;
	private GifImageView bigGifImageView;
	private ProgressBar bigProgressBar;
	private ArrayList<MessageReceiver> messageReceivers;
	private FileService fileService;
	private MessageService messageService;
	private File cropFile = null;
	private ComposeEditText captionEditText;
	private LinearLayout activityParentLayout;
	private EmojiPicker emojiPicker;
	private ImageButton cameraButton;
	private String cameraFilePath, videoFilePath;
	private boolean pickFromCamera, hasChanges = false;
	private View backgroundLayout;
	private int parentWidth = 0, parentHeight = 0;
	private int bigImagePos = 0;
	private boolean useExternalCamera;
	private VideoEditView videoEditView;
	private MenuItem settingsItem;
	private MediaFilterQuery lastMediaFilter;
	private List<MediaItem> initialItems;
	private TextView itemCountText;

	final ItemTouchHelper.SimpleCallback dragCallback = new ItemTouchHelper.SimpleCallback(ItemTouchHelper.RIGHT|ItemTouchHelper.LEFT, 0) {
		@Override
		public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
			int oldPosition = viewHolder.getBindingAdapterPosition();
			int newPosition = target.getBindingAdapterPosition();

			logger.debug("drag item position changed from {} to {}", oldPosition, newPosition);

			sendMediaAdapter.move(oldPosition, newPosition);

			return true;
		}

		@Override
		public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
			// we're not interested in swipes
		}
	};

	final RecyclerView.AdapterDataObserver adapterDataObserver = new RecyclerView.AdapterDataObserver() {
		@Override
		public void onItemRangeRemoved(int positionStart, int itemCount) {
			super.onItemRangeRemoved(positionStart, itemCount);
			updateItemCount();
		}

		@Override
		public void onItemRangeInserted(int positionStart, int itemCount) {
			super.onItemRangeInserted(positionStart, itemCount);
			updateItemCount();
		}

		private void updateItemCount() {
			if (itemCountText != null) {
				itemCountText.setText(getString(R.string.num_items_sected, Integer.toString(sendMediaAdapter.size())));
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		backgroundLayout = null;

		super.onCreate(savedInstanceState);
	}

	@Override
	protected boolean initActivity(Bundle savedInstanceState) {
		if (!super.initActivity(savedInstanceState)) {
			return false;
		}

		if (preferenceService.getEmojiStyle() != PreferenceService.EmojiStyle_ANDROID) {
			ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_parent).getRootView(), (v, insets) -> {

				logger.debug("system window top " + insets.getSystemWindowInsetTop() + " bottom " + insets.getSystemWindowInsetBottom());
				logger.debug("stable insets top " + insets.getStableInsetTop() + " bottom " + insets.getStableInsetBottom());

				if (insets.getSystemWindowInsetBottom() <= insets.getStableInsetBottom()) {
					onSoftKeyboardClosed();
				} else {
					onSoftKeyboardOpened(insets.getSystemWindowInsetBottom() - insets.getStableInsetBottom());
				}
				return insets;
			});
			addOnSoftKeyboardChangedListener(this);
		}

		final ActionBar actionBar = getSupportActionBar();
		if (actionBar == null) {
			finish();
			return false;
		}
		actionBar.setDisplayHomeAsUpEnabled(true);

		DeadlineListService hiddenChatsListService;
		try {
			this.fileService = ThreemaApplication.requireServiceManager().getFileService();
			this.messageService = ThreemaApplication.requireServiceManager().getMessageService();
			hiddenChatsListService = ThreemaApplication.requireServiceManager().getHiddenChatsListService();
		} catch (NullPointerException | ThreemaException e) {
			logger.error("Exception", e);
			finish();
			return false;
		}

		if (hiddenChatsListService == null) {
			logger.error("HiddenChatsListService not available.");
			finish();
			return false;
		}

		this.activityParentLayout = findViewById(R.id.activity_parent);

		Intent intent = getIntent();
		this.pickFromCamera = intent.getBooleanExtra(ThreemaApplication.INTENT_DATA_PICK_FROM_CAMERA, false);
		this.useExternalCamera = intent.getBooleanExtra(EXTRA_USE_EXTERNAL_CAMERA, false);
		this.messageReceivers = IntentDataUtil.getMessageReceiversFromIntent(intent);
		// check if we previously filtered media in MediaAttachActivity to reuse the filter when adding additional media items
		this.lastMediaFilter = IntentDataUtil.getLastMediaFilterFromIntent(intent);

		if (this.pickFromCamera && savedInstanceState == null) {
			launchCamera();
		}

		ArrayList<Uri> urilist = intent.getParcelableArrayListExtra(EXTRA_URLILIST);
		if (urilist != null) {
			intent.removeExtra(EXTRA_URLILIST);
		}

		initialItems = intent.getParcelableArrayListExtra(EXTRA_MEDIA_ITEMS);
		if (initialItems != null) {
			intent.removeExtra(EXTRA_MEDIA_ITEMS);
		}
		setResult(RESULT_CANCELED);

		boolean allReceiverChatsAreHidden = true;
		for (MessageReceiver messageReceiver : messageReceivers) {
			messageReceiver.validateSendingPermission(errorResId -> {
				messageReceivers.remove(messageReceiver);
				Toast.makeText(getApplicationContext(), errorResId, Toast.LENGTH_LONG).show();
			});
			if (allReceiverChatsAreHidden && !hiddenChatsListService.has(messageReceiver.getUniqueIdString())) {
				allReceiverChatsAreHidden = false;
			}
		}

		if (this.messageReceivers.size() < 1) {
			finish();
			return false;
		}

		SendMediaAdapter.ClickListener clickListener = new SendMediaAdapter.ClickListener() {
			@Override
			public void onItemClicked(int position, MediaItem item, @SendMediaAdapter.ViewType int itemViewType) {
				showBigImage(position);
			}

			@Override
			public void onDeleteKeyClicked(int position) {
				removeItem(position);
			}

			@Override
			public void onAddKeyClicked() {
				addImage();
			}
		};

		this.sendMediaAdapter = new SendMediaAdapter(
			this,
			clickListener
		);
		this.sendMediaAdapter.registerAdapterDataObserver(adapterDataObserver);

		if (savedInstanceState != null) {
			this.bigImagePos = savedInstanceState.getInt(STATE_BIGIMAGE_POS, 0);
			this.cameraFilePath = savedInstanceState.getString(STATE_CAMERA_FILE);
			this.videoFilePath = savedInstanceState.getString(STATE_VIDEO_FILE);
			Uri cropUri = savedInstanceState.getParcelable(STATE_CROP_FILE);
			if (cropUri != null) {
				this.cropFile = new File(cropUri.getPath());
			}
			this.initialItems = savedInstanceState.getParcelableArrayList(STATE_ITEMS);
		}

		this.bigImageView = findViewById(R.id.preview_image);
		this.bigGifImageView = findViewById(R.id.gif_image);
		this.videoEditView = findViewById(R.id.video_edit_view);
		this.bigProgressBar = findViewById(R.id.progress);
		this.itemCountText = findViewById(R.id.item_count);

		this.captionEditText = findViewById(R.id.caption_edittext);
		this.captionEditText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				ThreemaApplication.activityUserInteract(SendMediaActivity.this);
			}

			@Override
			public void afterTextChanged(Editable s) {
				if (s != null && bigImagePos < sendMediaAdapter.size()) {
					MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);
					if (mediaItem != null) {
						mediaItem.setCaption(s.toString());
					}
				}
			}
		});

		TextView recipientText = findViewById(R.id.recipient_text);

		this.cameraButton = findViewById(R.id.camera_button);
		this.cameraButton.setOnClickListener(v -> launchCamera());

		this.recyclerView = findViewById(R.id.item_list);
		this.recyclerView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));

		ItemTouchHelper itemTouchHelper = new ItemTouchHelper(dragCallback);
		itemTouchHelper.attachToRecyclerView(this.recyclerView);

		EmojiButton emojiButton = findViewById(R.id.emoji_button);

		if (preferenceService.getEmojiStyle() != PreferenceService.EmojiStyle_ANDROID) {
			emojiButton.setOnClickListener(v -> showEmojiPicker());

			this.emojiPicker = (EmojiPicker) ((ViewStub) findViewById(R.id.emoji_stub)).inflate();
			this.emojiPicker.init(ThreemaApplication.requireServiceManager().getEmojiService());
			emojiButton.attach(this.emojiPicker, true);
			this.emojiPicker.setEmojiKeyListener(new EmojiPicker.EmojiKeyListener() {
				@Override
				public void onBackspaceClick() {
					captionEditText.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
				}

				@Override
				public void onEmojiClick(String emojiCodeString) {
					captionEditText.addEmoji(emojiCodeString);
				}

				@Override
				public void onShowPicker() {
					showEmojiPicker();
				}
			});

			this.captionEditText.setOnClickListener(v -> {
				if (emojiPicker != null) {
					if (emojiPicker.isShown()) {
						if (ConfigUtils.isLandscape(this) &&
							!ConfigUtils.isTabletLayout()) {
							emojiPicker.hide();
						} else {
							openSoftKeyboard(emojiPicker, captionEditText);
						}
					}
				}
			});
			this.captionEditText.setOnEditorActionListener(
				(v, actionId, event) -> {
					if ((actionId == EditorInfo.IME_ACTION_SEND) ||
						(event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && preferenceService.isEnterToSend())) {
						sendMedia();
						return true;
					}
					return false;
				});
			emojiButton.setColorFilter(getResources().getColor(android.R.color.white));
		} else {
			emojiButton.setVisibility(View.GONE);
			this.captionEditText.setPadding(getResources().getDimensionPixelSize(R.dimen.no_emoji_button_padding_left), this.captionEditText.getPaddingTop(), this.captionEditText.getPaddingRight(), this.captionEditText.getPaddingBottom());
		}

		String recipients = getIntent().getStringExtra(ThreemaApplication.INTENT_DATA_TEXT);
		if (!TestUtil.empty(recipients)) {
			this.captionEditText.setHint(R.string.add_caption_hint);
			this.captionEditText.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) { }

				@Override
				public void afterTextChanged(Editable s) {
					if (s == null || s.length() == 0) {
						captionEditText.setHint(R.string.add_caption_hint);
					}
				}
			});
			recipientText.setText(getString(R.string.send_to, recipients));
		} else {
			findViewById(R.id.recipient_container).setVisibility(View.GONE);
		}

		SendButton sendButton = findViewById(R.id.send_button);
		sendButton.setOnClickListener(new DebouncedOnClickListener(500) {
			@Override
			public void onDebouncedClick(View v) {
				// avoid duplicates
				v.setEnabled(false);
				AnimationUtil.zoomOutAnimate(v);
				if (emojiPicker != null && emojiPicker.isShown()) {
					emojiPicker.hide();
				}
				sendMedia();
			}
		});
		sendButton.setEnabled(true);

		this.backgroundLayout = findViewById(R.id.background_layout);

		final ViewTreeObserver observer = backgroundLayout.getViewTreeObserver();
		observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
			@Override
			public void onGlobalLayout() {
				backgroundLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
				initUi(backgroundLayout, urilist, initialItems);
			}
		});

		return true;
	}

	private void showEmojiPicker() {
		if (isSoftKeyboardOpen()) {
			runOnSoftKeyboardClose(() -> {
				if (emojiPicker != null) {
					emojiPicker.show(loadStoredSoftKeyboardHeight());
				}
			});
			captionEditText.post(() -> EditTextUtil.hideSoftKeyboard(captionEditText));
		} else {
			if (emojiPicker != null) {
				if (emojiPicker.isShown()) {
					if (ConfigUtils.isLandscape(this) &&
						!ConfigUtils.isTabletLayout()) {
						emojiPicker.hide();
					} else {
						openSoftKeyboard(emojiPicker, captionEditText);
						if (getResources().getConfiguration().keyboard == Configuration.KEYBOARD_QWERTY) {
							emojiPicker.hide();
						}
					}
				} else {
					emojiPicker.show(loadStoredSoftKeyboardHeight());
				}
			}
		}
	}

	private void initUi(View backgroundLayout, List<Uri> urilist, List<MediaItem> mediaItems) {
		parentWidth = backgroundLayout.getWidth();
		parentHeight = backgroundLayout.getHeight();

		this.recyclerView.setAdapter(this.sendMediaAdapter);

		// add first image
		if (urilist != null && urilist.size() > 0) {
			addItemsByUriList(urilist);
		} else if (mediaItems != null && mediaItems.size() > 0) {
			addItemsByMediaItem(mediaItems, true);
		}

		if (this.pickFromCamera) {
			if (this.backgroundLayout != null) {
				this.backgroundLayout.postDelayed(() -> backgroundLayout.setVisibility(View.VISIBLE), 500);
			}
		} else {
			this.backgroundLayout.setVisibility(View.VISIBLE);
		}
	}

	private void showSettingsDropDown(final View view, final MediaItem mediaItem) {
		Context contextWrapper = new ContextThemeWrapper(this, R.style.Threema_PopupMenuStyle);
		PopupMenu popup = new PopupMenu(contextWrapper, view);

		popup.setOnMenuItemClickListener(item -> {
			mediaItem.setImageScale(item.getOrder());
			return true;
		});
		popup.inflate(R.menu.view_image_settings);

		@PreferenceService.ImageScale int currentScale = mediaItem.getImageScale();
		if (currentScale == PreferenceService.ImageScale_DEFAULT) {
			currentScale = preferenceService.getImageScale();
		}

		popup.getMenu().getItem(currentScale).setChecked(true);
		popup.show();
	}

	private void launchCamera() {
		if (ConfigUtils.requestCameraPermissions(this, null, PERMISSION_REQUEST_CAMERA)) {
			reallyLaunchCamera();
		}
	}

	@SuppressLint("UnsupportedChromeOsCameraSystemFeature")
	private void reallyLaunchCamera() {
		File cameraFile = null;
		File videoFile = null;
		try {
			cameraFile = fileService.createTempFile(".camera", ".jpg", false);
			this.cameraFilePath = cameraFile.getCanonicalPath();

			videoFile = fileService.createTempFile(".video", ".mp4", false);
			this.videoFilePath = videoFile.getCanonicalPath();
		} catch (IOException e) {
			logger.error("Exception", e);
			finish();
		}

		final Intent cameraIntent;
		final int requestCode;
		if (CameraUtil.isInternalCameraSupported() && !useExternalCamera) {
			// use internal camera
			cameraIntent = new Intent(this, CameraActivity.class);
			cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraFilePath);
			cameraIntent.putExtra(CameraActivity.EXTRA_VIDEO_OUTPUT, videoFilePath);
			requestCode = ThreemaActivity.ACTIVITY_ID_PICK_CAMERA_INTERNAL;
		} else {
			// use external camera
			PackageManager packageManager = getPackageManager();
			if (packageManager == null || !(packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
					packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))) {
				Toast.makeText(getApplicationContext(), R.string.no_camera_installed, Toast.LENGTH_LONG).show();
				finish();
				return;
			}

			cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
			cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileService.getShareFileUri(cameraFile, null));
			cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
			requestCode = ThreemaActivity.ACTIVITY_ID_PICK_CAMERA_EXTERNAL;
		}

		try {
			startActivityForResult(cameraIntent, requestCode);
			overridePendingTransition(0, 0);
		} catch (ActivityNotFoundException e) {
			logger.error("Exception", e);
			finish();
		}
	}

	private void addImage() {
		Intent intent = new Intent(getApplicationContext(), MediaSelectionActivity.class);
		// pass last media filter to open the chooser with the same selection.
		if (lastMediaFilter != null) {
			IntentDataUtil.addLastMediaFilterToIntent(intent, this.lastMediaFilter);
		}
		startActivityForResult(intent, ThreemaActivity.ACTIVITY_ID_PICK_MEDIA);
	}

	@Override
	protected void onResume() {
		super.onResume();

		// load main image
		this.backgroundLayout = findViewById(R.id.background_layout);
		if (this.backgroundLayout != null) {
			this.backgroundLayout.post(() -> showBigImage(bigImagePos));
		}
	}

	@Override
	public int getLayoutResource() {
		return R.layout.activity_send_media;
	}

	@Override
	public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
		updateMenu();

		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onCreateOptionsMenu(@NonNull Menu menu) {
		getToolbar().setTitle(R.string.send_media);
		getMenuInflater().inflate(R.menu.activity_send_media, menu);

		settingsItem = menu.findItem(R.id.settings);
		settingsItem.setOnMenuItemClickListener(item -> {
			new Handler().post(() -> {
				final View v = findViewById(R.id.settings);
				if (v != null) {
					MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);
					if (mediaItem != null) {
						showSettingsDropDown(v, mediaItem);
					}
				}
			});
			return true;
		});

		menu.findItem(R.id.flip).setOnMenuItemClickListener(new DebouncedOnMenuItemClickListener(IMAGE_ANIMATION_DURATION_MS * 2) {
			@Override
			public boolean onDebouncedMenuItemClick(MenuItem item) {
				if (bigImagePos < sendMediaAdapter.size()) {
					prepareFlip();
					return true;
				}
				return false;
			}
		});

		menu.findItem(R.id.rotate).setOnMenuItemClickListener(new DebouncedOnMenuItemClickListener(IMAGE_ANIMATION_DURATION_MS * 2) {
			@Override
			public boolean onDebouncedMenuItemClick(MenuItem item) {
				if (bigImagePos < sendMediaAdapter.size()) {
					prepareRotate();
					return true;
				}
				return false;
			}
		});

		menu.findItem(R.id.crop).setOnMenuItemClickListener(item -> {
			MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);
			if (mediaItem != null) {
				cropImage(mediaItem);
				return true;
			}
			return false;
		});

		menu.findItem(R.id.edit).setOnMenuItemClickListener(item -> {
			MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);
			if (mediaItem != null) {
				editImage(mediaItem);
				return true;
			}
			return false;
		});

		if (getToolbar().getNavigationIcon() != null) {
			getToolbar().getNavigationIcon().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
		}

		return super.onCreateOptionsMenu(menu);
	}

	private void prepareRotate() {
		if (bigImageView.getDrawable() == null) {
			return;
		}

		MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);
		if (mediaItem == null) {
			return;
		}

		int oldRotation = mediaItem.getRotation();
		int newRotation = ((oldRotation == 0 ? 360 : oldRotation) - 90) % 360;

		int height = bigImageView.getDrawable().getBounds().width();
		int width = bigImageView.getDrawable().getBounds().height();

		float screenAspectRatio = (float) parentWidth / (float) parentHeight;
		float imageAspectRatio = (float) width / (float) height;

		float scalingFactor;
		if (screenAspectRatio > imageAspectRatio) {
			scalingFactor = (float) parentHeight / (float) height;
		} else {
			scalingFactor = (float) parentWidth / (float) width;
		}

		bigImageView.animate().rotationBy(-90f)
			.scaleX(scalingFactor)
			.scaleY(scalingFactor)
			.setDuration(IMAGE_ANIMATION_DURATION_MS)
			.setInterpolator(new FastOutSlowInInterpolator())
			.setListener(new Animator.AnimatorListener() {
				@Override
				public void onAnimationStart(Animator animation) {}

				@Override
				public void onAnimationEnd(Animator animation) {
					MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);
					if (mediaItem != null) {
						mediaItem.setRotation(newRotation);
						showBigImage(bigImagePos, false);
						sendMediaAdapter.update(bigImagePos);
						hasChanges = true;
					}
				}

				@Override
				public void onAnimationCancel(Animator animation) {}

				@Override
				public void onAnimationRepeat(Animator animation) {}
			});
	}

	private void prepareFlip() {
		if (bigImageView.getDrawable() == null) {
			return;
		}

		bigImageView.animate().rotationY(180f)
			.setDuration(IMAGE_ANIMATION_DURATION_MS)
			.setInterpolator(new FastOutSlowInInterpolator())
			.setListener(new Animator.AnimatorListener() {
				@Override
				public void onAnimationStart(Animator animation) {}

				@Override
				public void onAnimationEnd(Animator animation) {
					MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);
					if (mediaItem != null) {
						flip(mediaItem);
						showBigImage(bigImagePos, false);
						sendMediaAdapter.update(bigImagePos);
						hasChanges = true;
					}
				}

				@Override
				public void onAnimationCancel(Animator animation) {}

				@Override
				public void onAnimationRepeat(Animator animation) {}
			});
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			confirmQuit();
		}
		return super.onOptionsItemSelected(item);
	}

	private void flip(@NonNull MediaItem item) {
		int currentFlip = item.getFlip();

		if (item.getRotation() == 90 || item.getRotation() == 270) {
			if ((currentFlip & FLIP_VERTICAL) == FLIP_VERTICAL) {
				// clear vertical flag
				currentFlip &= ~FLIP_VERTICAL;
			} else {
				currentFlip |= FLIP_VERTICAL;
			}
		} else {
			if ((currentFlip & FLIP_HORIZONTAL) == FLIP_HORIZONTAL) {
				// clear horizontal flag
				currentFlip &= ~FLIP_HORIZONTAL;
			} else {
				currentFlip |= FLIP_HORIZONTAL;
			}
		}
		item.setFlip(currentFlip);
	}

	@SuppressLint("StaticFieldLeak")
	private void addItemsByUriList(List<Uri> uriList) {
		if (uriList.size() > 0) {
			new AsyncTask<Void, Void, List<MediaItem>>() {
				boolean capacityExceeded = false;

				@Override
				protected void onPreExecute() {
					if (sendMediaAdapter.size() + uriList.size() > MAX_EDITABLE_IMAGES) {
						Snackbar.make((View) recyclerView.getParent(), String.format(getString(R.string.max_images_reached), MAX_EDITABLE_IMAGES), BaseTransientBottomBar.LENGTH_LONG).show();
					}
				}

				@Override
				protected List<MediaItem> doInBackground(Void... voids) {
					List<MediaItem> itemList = new ArrayList<>();
					int numExistingItems = sendMediaAdapter.size();

					for (Uri uri : uriList) {
						if (uri != null) {
							if (isDuplicate(sendMediaAdapter.getItems(), uri) || isDuplicate(itemList, uri)) {
								continue;
							}

							if (numExistingItems + itemList.size() >= MAX_EDITABLE_IMAGES) {
								capacityExceeded = true;
								break;
							}

							Uri fixedUri = FileUtil.getFixedContentUri(getApplicationContext(), uri);
							String typeUtil = FileUtil.getMimeTypeFromUri(getApplicationContext(), fixedUri);
							int type;
							if (MimeUtil.isVideoFile(typeUtil)){
								type = MediaItem.TYPE_VIDEO;
							} else if (MimeUtil.isGifFile(typeUtil)){
								type = MediaItem.TYPE_GIF;
							} else{
								type = MediaItem.TYPE_IMAGE;
							}
							logger.debug("type is " );

							BitmapUtil.ExifOrientation exifOrientation = BitmapUtil.getExifOrientation(getApplicationContext(), fixedUri);

							MediaItem mediaItem = new MediaItem(fixedUri, type);
							mediaItem.setOriginalUri(uri);
							mediaItem.setExifRotation((int) exifOrientation.getRotation());
							mediaItem.setExifFlip(exifOrientation.getFlip());
							mediaItem.setCaption("");

							if (MimeUtil.isVideoFile(typeUtil)) {
								// do not use automatic resource management on MediaMetadataRetriever
								MediaMetadataRetriever metaDataRetriever = new MediaMetadataRetriever();
								try {
									metaDataRetriever.setDataSource(ThreemaApplication.getAppContext(), mediaItem.getUri());
									mediaItem.setDurationMs(Integer.parseInt(metaDataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
								} catch (Exception ignored) {
								} finally {
									metaDataRetriever.release();
								}
							}
							itemList.add(mediaItem);
						}
					}
					return itemList;
				}

				@Override
				protected void onPostExecute(List<MediaItem> itemList) {
					if (sendMediaAdapter != null) {
						sendMediaAdapter.add(itemList);
						if (capacityExceeded) {
							Snackbar.make((View) recyclerView.getParent(), String.format(getString(R.string.max_images_reached), MAX_EDITABLE_IMAGES), BaseTransientBottomBar.LENGTH_LONG).show();
						}
						updateMenu();
						showBigImage(sendMediaAdapter.size() - 1);
					}
				}
			}.execute();
		}
	}

	@SuppressLint("StaticFieldLeak")
	private void addItemsByMediaItem(List<MediaItem> incomingMediaItems, boolean prepend) {
		if (incomingMediaItems.size() > 0) {
			new AsyncTask<Void, Void, List<MediaItem>>() {
				@Override
				protected List<MediaItem> doInBackground(Void... voids) {
					List<MediaItem> itemList = new ArrayList<>();

					for (MediaItem incomingMediaItem : incomingMediaItems) {
						if (incomingMediaItem.getUri() != null) {
							if (isDuplicate(sendMediaAdapter.getItems(), incomingMediaItem.getUri())) {
								continue;
							}

							BitmapUtil.ExifOrientation exifOrientation = BitmapUtil.getExifOrientation(getApplicationContext(), incomingMediaItem.getUri());
							incomingMediaItem.setExifRotation((int) exifOrientation.getRotation());
							incomingMediaItem.setExifFlip(exifOrientation.getFlip());

							if (MimeUtil.isVideoFile(incomingMediaItem.getMimeType())) {
								// do not use automatic resource management on MediaMetadataRetriever
								MediaMetadataRetriever metaDataRetriever = new MediaMetadataRetriever();
								try {
									metaDataRetriever.setDataSource(ThreemaApplication.getAppContext(), incomingMediaItem.getUri());
									incomingMediaItem.setDurationMs(Integer.parseInt(metaDataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
								} catch (Exception ignored) {
								} finally {
									metaDataRetriever.release();
								}
							}

							itemList.add(incomingMediaItem);
						}
					}
					return itemList;
				}

				@Override
				protected void onPostExecute(List<MediaItem> itemList) {
					if (sendMediaAdapter.size() + itemList.size() > MAX_EDITABLE_IMAGES) {
						Snackbar.make((View) recyclerView.getParent(), String.format(getString(R.string.max_images_reached), MAX_EDITABLE_IMAGES), BaseTransientBottomBar.LENGTH_LONG).show();
					} else {
						if (prepend) {
							sendMediaAdapter.add(itemList, 0);
						} else {
							sendMediaAdapter.add(itemList);
						}
						updateMenu();
						showBigImage(sendMediaAdapter.size() - 1);
					}
				}
			}.execute();
		}
	}

	@SuppressLint("NewApi")
	@Override
	public void onActivityResult(int requestCode, int resultCode,
								 final Intent intent) {

		if (resultCode == Activity.RESULT_OK) {
			hasChanges = true;
			switch (requestCode) {
				case CropImageActivity.REQUEST_CROP:
				case ThreemaActivity.ACTIVITY_ID_PAINT:
					backgroundLayout.post(() -> {
						MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);

						if (mediaItem != null) {
							mediaItem.setUri(Uri.fromFile(cropFile));
							mediaItem.setRotation(0);
							mediaItem.setExifRotation(0);
							mediaItem.setFlip(BitmapUtil.FLIP_NONE);
							mediaItem.setExifFlip(BitmapUtil.FLIP_NONE);
							sendMediaAdapter.update(bigImagePos);
						}
					});
					break;
				case ThreemaActivity.ACTIVITY_ID_PICK_CAMERA_EXTERNAL:
				case ThreemaActivity.ACTIVITY_ID_PICK_CAMERA_INTERNAL:
					if (ConfigUtils.supportsVideoCapture() && intent != null && intent.getBooleanExtra(CameraActivity.EXTRA_VIDEO_RESULT, false)) {
						// it's a video file
						if (!TestUtil.empty(this.videoFilePath)) {
							File videoFile = new File(this.videoFilePath);
							if (videoFile.exists() && videoFile.length() > 0) {
								final Uri videoUri = Uri.fromFile(videoFile);
								if (videoUri != null) {
									final int position = addItemFromCamera(MediaItem.TYPE_VIDEO_CAM, videoUri, null);
									showBigImage(position);
									break;
								}
							}
						}
					} else {
						if (!TestUtil.empty(this.cameraFilePath)) {
							final Uri cameraUri = Uri.fromFile(new File(this.cameraFilePath));
							if (cameraUri != null) {
								BitmapUtil.ExifOrientation exifOrientation = BitmapUtil.getExifOrientation(this, cameraUri);
								if (requestCode != ThreemaActivity.ACTIVITY_ID_PICK_CAMERA_EXTERNAL) {
									if (bigImageView != null) {
										bigImageView.setVisibility(View.GONE);
									}
									if (bigGifImageView != null) {
										bigGifImageView.setVisibility(View.GONE);
									}
								}

								final int position = addItemFromCamera(MediaItem.TYPE_IMAGE_CAM, cameraUri, exifOrientation);
								showBigImage(position);
								break;
							}
						}
					}
					if (sendMediaAdapter.size() <= 0) {
						finish();
					}
					break;
				case ThreemaActivity.ACTIVITY_ID_PICK_MEDIA:
					ArrayList<MediaItem> mediaItemsList = intent.getParcelableArrayListExtra(EXTRA_MEDIA_ITEMS);
					if (mediaItemsList != null){
						addItemsByMediaItem(mediaItemsList, false);
					}
					// update last media filter used to add media items.
					this.lastMediaFilter = IntentDataUtil.getLastMediaFilterFromIntent(intent);
				default:
					break;
			}
		} else {
			if (sendMediaAdapter.size() <= 0) {
				finish();
			}
		}

		super.onActivityResult(requestCode, resultCode, intent);
	}

	@UiThread
	private void sendMedia() {
		if (sendMediaAdapter.size() < 1) {
			return;
		}

		messageService.sendMediaAsync(sendMediaAdapter.getItems(), messageReceivers, null);

		// return last media filter to chat via intermediate hop through MediaAttachActivity
		if (lastMediaFilter != null) {
			Intent lastMediaSelectionResult = IntentDataUtil.addLastMediaFilterToIntent(new Intent(), this.lastMediaFilter);
			setResult(RESULT_OK, lastMediaSelectionResult);
		} else {
			setResult(RESULT_OK);
		}
		finish();
	}

	private void removeItem(int position) {
		if (sendMediaAdapter != null) {
			sendMediaAdapter.remove(position);

			if (sendMediaAdapter.size() > 0) {
				showBigImage(position < sendMediaAdapter.size() ? position : 0);
				updateMenu();
			} else {
				// no items left - goodbye
				finish();
			}
		}
	}

	@UiThread
	private int addItemFromCamera(int type, @NonNull Uri imageUri, BitmapUtil.ExifOrientation exifOrientation) {
		if (sendMediaAdapter == null) {
			return 0;
		}

		if (sendMediaAdapter.size() >= MAX_EDITABLE_IMAGES) {
			Snackbar.make((View) recyclerView.getParent(), String.format(getString(R.string.max_images_reached), MAX_EDITABLE_IMAGES), BaseTransientBottomBar.LENGTH_LONG).show();
		}

		MediaItem item = new MediaItem(imageUri, type);
		item.setOriginalUri(imageUri);
		if (exifOrientation != null) {
			item.setExifRotation((int) exifOrientation.getRotation());
			item.setExifFlip(exifOrientation.getFlip());
		}

		if (type == MediaItem.TYPE_VIDEO_CAM) {
			item.setMimeType(MimeUtil.MIME_TYPE_VIDEO_MP4);
		} else {
			item.setMimeType(MimeUtil.MIME_TYPE_IMAGE_JPG);
		}

		if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(imageUri.getScheme())) {
			item.setDeleteAfterUse(true);
		}

		sendMediaAdapter.add(item);

		return sendMediaAdapter.size() - 1;
	}

	private void cropImage(@NonNull MediaItem mediaItem) {
		Uri imageUri = mediaItem.getUri();

		try {
			cropFile = fileService.createTempFile(".crop", ".png");

			Intent intent = new Intent(this, CropImageActivity.class);
			intent.setData(imageUri);
			intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(cropFile));
			intent.putExtra(ThreemaApplication.EXTRA_ORIENTATION, mediaItem.getRotation());
			intent.putExtra(ThreemaApplication.EXTRA_FLIP, mediaItem.getFlip());
			intent.putExtra(CropImageActivity.FORCE_DARK_THEME, true);

			startActivityForResult(intent, CropImageActivity.REQUEST_CROP);
			overridePendingTransition(R.anim.medium_fade_in, R.anim.medium_fade_out);
		} catch (IOException e) {
			logger.debug("Unable to create temp file for crop");
		}
	}

	private void editImage(@NonNull MediaItem mediaItem) {
		try {
			cropFile = fileService.createTempFile(".edit", ".png");

			Intent intent = new Intent(this, ImagePaintActivity.class);
			intent.putExtra(Intent.EXTRA_STREAM, mediaItem);
			intent.putExtra(ThreemaApplication.EXTRA_OUTPUT_FILE, Uri.fromFile(cropFile));
			intent.putExtra(ThreemaApplication.EXTRA_ORIENTATION, mediaItem.getRotation());
			intent.putExtra(ThreemaApplication.EXTRA_FLIP, mediaItem.getFlip());
			intent.putExtra(ThreemaApplication.EXTRA_EXIF_ORIENTATION, mediaItem.getExifRotation());
			intent.putExtra(ThreemaApplication.EXTRA_EXIF_FLIP,mediaItem.getExifFlip());

			startActivityForResult(intent, ThreemaActivity.ACTIVITY_ID_PAINT);
			overridePendingTransition(0, R.anim.slow_fade_out);
		} catch (IOException e) {
			logger.debug("Unable to create temp file for crop");
		}
	}

	private void selectImage(final int position) {
		if (recyclerView != null) {
			recyclerView.post(() -> {
				try {
					sendMediaAdapter.setItemChecked(position);
				} catch (Exception e) {
					logger.error("Exception", e);
				}
			});
		}
	}

	private void updateMenu() {
		if (this.cameraButton != null) {
			this.cameraButton.setVisibility(sendMediaAdapter.size() < MAX_EDITABLE_IMAGES ? View.VISIBLE : View.GONE);
		}

		MediaItem mediaItem = sendMediaAdapter.getItem(bigImagePos);
		if (sendMediaAdapter.size() > 0 && mediaItem != null) {
			boolean canEdit = mediaItem.getType() == TYPE_IMAGE ||mediaItem.getType() == TYPE_IMAGE_CAM;
			boolean canSettings = mediaItem.getType() == TYPE_IMAGE;

			getToolbar().getMenu().setGroupVisible(R.id.group_tools, canEdit);

			if (settingsItem != null) {
				settingsItem.setVisible(canSettings);
			}
		} else {
			getToolbar().getMenu().setGroupVisible(R.id.group_tools, false);
		}
	}

	private void showBigVideo(MediaItem item) {
		this.bigImageView.setVisibility(View.GONE);
		this.bigGifImageView.setVisibility(View.GONE);
		this.videoEditView.setVisibility(View.VISIBLE);
		this.videoEditView.setVideo(item);
		logger.debug("show video " + item.getDurationMs());
	}

	private void showBigImage(final int position) {
		showBigImage(position, true);
	}

	private void showBigImage(final int position, boolean showProgressBar) {
		logger.debug("showBigImage: " + position);
		if (sendMediaAdapter.size() <= 0) {
			logger.debug("Adapter is empty");
			return;
		}

		MediaItem mediaItem = sendMediaAdapter.getItem(position);
		if (mediaItem == null) {
			logger.debug("Item is null");
			return;
		}

		bigImagePos = position;

		updateMenu();

		if (mediaItem.getType() == MediaItem.TYPE_VIDEO || mediaItem.getType() == MediaItem.TYPE_VIDEO_CAM) {
			showBigVideo(mediaItem);
		}
		else {
			this.videoEditView.setVisibility(View.GONE);

			if (mediaItem.getType() == TYPE_GIF) {
				bigProgressBar.setVisibility(View.GONE);
				bigImageView.setVisibility(View.GONE);
				try {
					bigGifImageView.setImageURI(mediaItem.getUri());
					bigGifImageView.setVisibility(View.VISIBLE);
				} catch (Exception e) {
					// may crash with a SecurityException on some exotic devices
					logger.error("Error setting GIF", e);
				}
			} else {
				BitmapWorkerTaskParams bitmapParams = new BitmapWorkerTaskParams();
				bitmapParams.imageUri = mediaItem.getUri();
				bitmapParams.width = parentWidth;
				bitmapParams.height = parentHeight;
				bitmapParams.contentResolver = getContentResolver();
				bitmapParams.mutable = false;
				bitmapParams.flip = mediaItem.getFlip();
				bitmapParams.orientation = mediaItem.getRotation();
				bitmapParams.exifFlip = mediaItem.getExifFlip();
				bitmapParams.exifOrientation = mediaItem.getExifRotation();

				logger.debug("showBigImage uri: " + bitmapParams.imageUri);

				if (showProgressBar) {
					bigProgressBar.setVisibility(View.VISIBLE);
				}

				// load main image
				new BitmapWorkerTask(bigImageView) {
					@Override
					protected void onPostExecute(Bitmap bitmap) {
						super.onPostExecute(bitmap);
						bigProgressBar.setVisibility(View.GONE);
						bigImageView.setRotation(0f);
						bigImageView.setScaleX(1f);
						bigImageView.setScaleY(1f);
						bigImageView.setRotationY(0f);
						bigImageView.setVisibility(View.VISIBLE);
						bigGifImageView.setVisibility(View.GONE);
					}
				}.execute(bitmapParams);
			}
		}

		selectImage(bigImagePos);
		updateMenu();

		String caption = mediaItem.getCaption();
		captionEditText.setText(null);

		if (!TestUtil.empty(caption)) {
			captionEditText.append(caption);
		}
	}

	@Override
	public void onBackPressed() {
		if (emojiPicker != null && emojiPicker.isShown()) {
			emojiPicker.hide();
		} else {
			confirmQuit();
		}
	}

	private void confirmQuit() {
		if (hasChanges) {
			GenericAlertDialog dialogFragment = GenericAlertDialog.newInstance(
					R.string.discard_changes_title,
					R.string.discard_changes,
					R.string.yes,
					R.string.no);
			dialogFragment.show(getSupportFragmentManager(), DIALOG_TAG_QUIT_CONFIRM);
		} else {
			finish();
		}
	}

	private boolean isDuplicate(List<MediaItem> list, Uri uri) {
		// do not allow the same image twice
		for (int j = 0; j < list.size(); j++) {
			Uri originalUri = list.get(j).getOriginalUri();
			if (list.get(j).getUri().equals(uri) ||
				(originalUri != null &&
					originalUri.equals(uri))) {
				Snackbar.make((View) recyclerView.getParent(), getString(R.string.image_already_added), BaseTransientBottomBar.LENGTH_LONG).show();
				return true;
			}
		}
		return false;
	}

	@Override
	protected void onDestroy() {
		new Thread(() -> VideoTimelineCache.getInstance().flush()).start();

		if (preferenceService.getEmojiStyle() != PreferenceService.EmojiStyle_ANDROID) {
			removeAllListeners();
		}
		super.onDestroy();
	}

	@Override
	public void onYes(String tag, Object data) {
		finish();
	}

	@Override
	public void onNo(String tag, Object data) {}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		outState.putInt(STATE_BIGIMAGE_POS, this.bigImagePos);
		outState.putParcelableArrayList(STATE_ITEMS, (ArrayList<? extends Parcelable>) this.sendMediaAdapter.getItems());
		outState.putString(STATE_CAMERA_FILE, this.cameraFilePath);
		outState.putString(STATE_VIDEO_FILE, this.videoFilePath);
		if (this.cropFile != null) {
			outState.putParcelable(STATE_CROP_FILE, Uri.fromFile(this.cropFile));
		}
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == PERMISSION_REQUEST_CAMERA) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				reallyLaunchCamera();
			} else {
				if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
					ConfigUtils.showPermissionRationale(this, activityParentLayout, R.string.permission_camera_photo_required);
				}
			}
		}
	}

	@Override
	public void onKeyboardShown() {
		if (emojiPicker != null && emojiPicker.isShown()) {
			emojiPicker.onKeyboardShown();
		}
	}

	@Override
	public void onKeyboardHidden() { }
}
