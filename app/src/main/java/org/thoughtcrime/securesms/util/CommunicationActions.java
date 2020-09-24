package org.thoughtcrime.securesms.util;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.TaskStackBuilder;
import androidx.fragment.app.FragmentActivity;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.WebRtcCallActivity;
import org.thoughtcrime.securesms.conversation.ConversationActivity;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.ui.invitesandrequests.joining.GroupJoinBottomSheetDialogFragment;
import org.thoughtcrime.securesms.groups.ui.invitesandrequests.joining.GroupJoinUpdateRequiredBottomSheetDialogFragment;
import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.WebRtcCallService;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;

public class CommunicationActions {

  private static final String TAG = Log.tag(CommunicationActions.class);

  public static void startVoiceCall(@NonNull FragmentActivity activity, @NonNull Recipient recipient) {
    if (TelephonyUtil.isAnyPstnLineBusy(activity)) {
      Toast.makeText(activity,
                     R.string.CommunicationActions_a_cellular_call_is_already_in_progress,
                     Toast.LENGTH_SHORT)
           .show();
      return;
    }

    WebRtcCallService.isCallActive(activity, new ResultReceiver(new Handler(Looper.getMainLooper())) {
      @Override
      protected void onReceiveResult(int resultCode, Bundle resultData) {
        if (resultCode == 1) {
          startCallInternal(activity, recipient, false);
        } else {
          new AlertDialog.Builder(activity)
                         .setMessage(R.string.CommunicationActions_start_voice_call)
                         .setPositiveButton(R.string.CommunicationActions_call, (d, w) -> startCallInternal(activity, recipient, false))
                         .setNegativeButton(R.string.CommunicationActions_cancel, (d, w) -> d.dismiss())
                         .setCancelable(true)
                         .show();
        }
      }
    });
  }

  public static void startVideoCall(@NonNull FragmentActivity activity, @NonNull Recipient recipient) {
    if (TelephonyUtil.isAnyPstnLineBusy(activity)) {
      Toast.makeText(activity,
                     R.string.CommunicationActions_a_cellular_call_is_already_in_progress,
                     Toast.LENGTH_SHORT)
           .show();
      return;
    }

    WebRtcCallService.isCallActive(activity, new ResultReceiver(new Handler(Looper.getMainLooper())) {
      @Override
      protected void onReceiveResult(int resultCode, Bundle resultData) {
        startCallInternal(activity, recipient, resultCode != 1);
      }
    });
  }

  public static void startConversation(@NonNull Context context, @NonNull Recipient recipient, @Nullable String text) {
    startConversation(context, recipient, text, null);
  }

  public static void startConversation(@NonNull  Context          context,
                                       @NonNull  Recipient        recipient,
                                       @Nullable String           text,
                                       @Nullable TaskStackBuilder backStack)
  {
    new AsyncTask<Void, Void, Long>() {
      @Override
      protected Long doInBackground(Void... voids) {
        return DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
      }

      @Override
      protected void onPostExecute(Long threadId) {
        Intent intent = ConversationActivity.buildIntent(context, recipient.getId(), threadId);

        if (!TextUtils.isEmpty(text)) {
          intent.putExtra(ConversationActivity.TEXT_EXTRA, text);
        }

        if (backStack != null) {
          backStack.addNextIntent(intent);
          backStack.startActivities();
        } else {
          context.startActivity(intent);
        }
      }
    }.execute();
  }

  public static void startInsecureCall(@NonNull Activity activity, @NonNull Recipient recipient) {
    new AlertDialog.Builder(activity)
                   .setTitle(R.string.CommunicationActions_insecure_call)
                   .setMessage(R.string.CommunicationActions_carrier_charges_may_apply)
                   .setPositiveButton(R.string.CommunicationActions_call, (d, w) -> {
                     d.dismiss();
                     startInsecureCallInternal(activity, recipient);
                   })
                   .setNegativeButton(R.string.CommunicationActions_cancel, (d, w) -> d.dismiss())
                   .show();
  }

  public static void composeSmsThroughDefaultApp(@NonNull Context context, @NonNull Recipient recipient, @Nullable String text) {
    Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + recipient.requireSmsAddress()));
    if (text != null) {
      intent.putExtra("sms_body", text);
    }
    context.startActivity(intent);
  }

  public static void openBrowserLink(@NonNull Context context, @NonNull String link) {
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
      context.startActivity(intent);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(context, R.string.CommunicationActions_no_browser_found, Toast.LENGTH_SHORT).show();
    }
  }

  public static void openEmail(@NonNull Context context, @NonNull String address, @Nullable String subject, @Nullable String body) {
    Intent intent = new Intent(Intent.ACTION_SENDTO);
    intent.setData(Uri.parse("mailto:"));
    intent.putExtra(Intent.EXTRA_EMAIL, new String[]{ address });
    intent.putExtra(Intent.EXTRA_SUBJECT, Util.emptyIfNull(subject));
    intent.putExtra(Intent.EXTRA_TEXT, Util.emptyIfNull(body));

    context.startActivity(intent);
  }

  /**
   * If the url is a group link it will handle it.
   * If the url is a malformed group link, it will assume Signal needs to update.
   * Otherwise returns false, indicating was not a group link.
   */
  public static boolean handlePotentialGroupLinkUrl(@NonNull FragmentActivity activity, @NonNull String potentialGroupLinkUrl) {
    try {
      GroupInviteLinkUrl groupInviteLinkUrl = GroupInviteLinkUrl.fromUrl(potentialGroupLinkUrl);

      if (groupInviteLinkUrl == null) {
        return false;
      }

      handleGroupLinkUrl(activity, groupInviteLinkUrl);
      return true;
    } catch (GroupInviteLinkUrl.InvalidGroupLinkException e) {
      Log.w(TAG, "Could not parse group URL", e);
      Toast.makeText(activity, R.string.GroupJoinUpdateRequiredBottomSheetDialogFragment_group_link_is_not_valid, Toast.LENGTH_SHORT).show();
      return true;
    } catch (GroupInviteLinkUrl.UnknownGroupLinkVersionException e) {
      Log.w(TAG, "Group link is for an advanced version", e);
      GroupJoinUpdateRequiredBottomSheetDialogFragment.show(activity.getSupportFragmentManager());
      return true;
    }
  }

  public static void handleGroupLinkUrl(@NonNull FragmentActivity activity,
                                        @NonNull GroupInviteLinkUrl groupInviteLinkUrl)
  {
    GroupId.V2 groupId = GroupId.v2(groupInviteLinkUrl.getGroupMasterKey());

    SimpleTask.run(SignalExecutors.BOUNDED, () -> {
      GroupDatabase.GroupRecord group = DatabaseFactory.getGroupDatabase(activity)
                                                       .getGroup(groupId)
                                                       .orNull();

      return group != null && group.isActive() ? Recipient.resolved(group.getRecipientId())
                                               : null;
    },
    recipient -> {
      if (recipient != null) {
        CommunicationActions.startConversation(activity, recipient, null);
        Toast.makeText(activity, R.string.GroupJoinBottomSheetDialogFragment_you_are_already_a_member, Toast.LENGTH_SHORT).show();
      } else {
        GroupJoinBottomSheetDialogFragment.show(activity.getSupportFragmentManager(), groupInviteLinkUrl);
      }
    });
  }

  private static void startInsecureCallInternal(@NonNull Activity activity, @NonNull Recipient recipient) {
    try {
      Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + recipient.requireSmsAddress()));
      activity.startActivity(dialIntent);
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, anfe);
      Dialogs.showAlertDialog(activity,
                              activity.getString(R.string.ConversationActivity_calls_not_supported),
                              activity.getString(R.string.ConversationActivity_this_device_does_not_appear_to_support_dial_actions));
    }
  }

  private static void startCallInternal(@NonNull FragmentActivity activity, @NonNull Recipient recipient, boolean isVideo) {
    if (isVideo) startVideoCallInternal(activity, recipient);
    else         startAudioCallInternal(activity, recipient);
  }

  private static void startAudioCallInternal(@NonNull FragmentActivity activity, @NonNull Recipient recipient) {
    Permissions.with(activity)
               .request(Manifest.permission.RECORD_AUDIO)
               .ifNecessary()
               .withRationaleDialog(activity.getString(R.string.ConversationActivity__to_call_s_signal_needs_access_to_your_microphone, recipient.getDisplayName(activity)),
                   R.drawable.ic_mic_solid_24)
               .withPermanentDenialDialog(activity.getString(R.string.ConversationActivity__to_call_s_signal_needs_access_to_your_microphone, recipient.getDisplayName(activity)))
               .onAllGranted(() -> {
                 Intent intent = new Intent(activity, WebRtcCallService.class);
                 intent.setAction(WebRtcCallService.ACTION_OUTGOING_CALL)
                       .putExtra(WebRtcCallService.EXTRA_REMOTE_PEER, new RemotePeer(recipient.getId()))
                       .putExtra(WebRtcCallService.EXTRA_OFFER_TYPE, OfferMessage.Type.AUDIO_CALL.getCode());
                 activity.startService(intent);

                 MessageSender.onMessageSent();

                 Intent activityIntent = new Intent(activity, WebRtcCallActivity.class);

                 activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                 activity.startActivity(activityIntent);
               })
               .execute();
  }

  private static void startVideoCallInternal(@NonNull FragmentActivity activity, @NonNull Recipient recipient) {
    Permissions.with(activity)
               .request(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
               .ifNecessary()
               .withRationaleDialog(activity.getString(R.string.ConversationActivity_signal_needs_the_microphone_and_camera_permissions_in_order_to_call_s, recipient.getDisplayName(activity)),
                                    R.drawable.ic_mic_solid_24,
                                    R.drawable.ic_video_solid_24_tinted)
               .withPermanentDenialDialog(activity.getString(R.string.ConversationActivity_signal_needs_the_microphone_and_camera_permissions_in_order_to_call_s, recipient.getDisplayName(activity)))
               .onAllGranted(() -> {
                 Intent intent = new Intent(activity, WebRtcCallService.class);
                 intent.setAction(WebRtcCallService.ACTION_PRE_JOIN_CALL)
                       .putExtra(WebRtcCallService.EXTRA_REMOTE_PEER, new RemotePeer(recipient.getId()))
                       .putExtra(WebRtcCallService.EXTRA_OFFER_TYPE, OfferMessage.Type.VIDEO_CALL.getCode());
                 activity.startService(intent);

                 Intent activityIntent = new Intent(activity, WebRtcCallActivity.class);

                 activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                     .putExtra(WebRtcCallActivity.EXTRA_ENABLE_VIDEO_IF_AVAILABLE, true);

                 activity.startActivity(activityIntent);
               })
               .execute();
  }
}
