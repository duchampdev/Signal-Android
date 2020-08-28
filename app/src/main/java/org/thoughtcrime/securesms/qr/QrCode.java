package org.thoughtcrime.securesms.qr;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import org.thoughtcrime.securesms.logging.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QrCode {

  public static final String TAG = QrCode.class.getSimpleName();

  public static @NonNull Bitmap create(String data) {
    return create(data, Color.BLACK);
  }

  public static @NonNull Bitmap create(String data, @ColorInt int foregroundColor) {
    try {
      BitMatrix result = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 512, 512);
      Bitmap    bitmap = Bitmap.createBitmap(result.getWidth(), result.getHeight(), Bitmap.Config.ARGB_8888);

      for (int y = 0; y < result.getHeight(); y++) {
        for (int x = 0; x < result.getWidth(); x++) {
          if (result.get(x, y)) {
            bitmap.setPixel(x, y, foregroundColor);
          }
        }
      }

      return bitmap;
    } catch (WriterException e) {
      Log.w(TAG, e);
      return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
    }
  }

}
