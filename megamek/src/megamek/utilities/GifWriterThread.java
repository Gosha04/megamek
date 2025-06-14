/*
 * Copyright (c) 2025 - The MegaMek Team. All Rights Reserved.
 *
 *  This file is part of MegaMek.
 *
 *  MekHQ is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  MekHQ is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with MekHQ. If not, see <http://www.gnu.org/licenses/>.
 */
package megamek.utilities;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.util.StringUtil;
import megamek.logging.MMLogger;

/**
 * Thread that writes frames to a GIF file.
 *
 * @author Luana Coppio
 */
public class GifWriterThread extends Thread {
    private static final MMLogger LOGGER = MMLogger.create(GifWriter.class);

    private record Frame(BufferedImage image, long duration) {
    }

    private final GifWriter gifWriter;
    private final Deque<Frame> imageDeque = new ConcurrentLinkedDeque<>();
    private boolean isLive = true;
    private boolean forceInterrupt = false;
    public static final String CG_FILE_EXTENSION_GIF = ".gif";
    public static final String CG_FILE_PATH_GIF = "gif";

    /**
     * Creates a new GifWriterThread.
     *
     * @param gifWriter the GIF writer
     * @param name      the thread name
     */
    public GifWriterThread(GifWriter gifWriter, String name) {
        super(name);
        this.gifWriter = gifWriter;
    }

    /**
     * Adds a frame to the GIF.
     *
     * @param image          the frame image
     * @param durationMillis the frame duration in milliseconds
     */
    public void addFrame(BufferedImage image, long durationMillis) {
        synchronized (this) {
            imageDeque.add(new Frame(image, durationMillis));
            notifyAll();
        }
    }

    @Override
    public void run() {
        try {
            while (isLive) {
                try {
                    synchronized (this) {
                        while (imageDeque.isEmpty() && gifWriter.isLive() && isLive) {
                            wait();
                        }
                        if (!gifWriter.isLive()) {
                            break;
                        }
                        Frame frame = imageDeque.pollFirst();
                        if (frame == null) {
                            continue;
                        }
                        gifWriter.appendFrame(frame.image(), frame.duration());
                    }
                } catch (InterruptedException | IOException e) {
                    break;
                }
            }
        } finally {
            gifWriter.close();
            imageDeque.clear();
            if (!forceInterrupt) {
                try {
                    saveGifNag();
                } catch (Exception e) {
                    LOGGER.error(e, "Error deleting gif or opening JOptionPane");
                }
            }
            isLive = false;
            forceInterrupt = false;
        }
    }

    private void saveGifNag() {
        if (GUIPreferences.getInstance().getGifGameSummaryMinimap()) {
            int response = JOptionPane.showConfirmDialog(null,
                  Messages.getString("ClientGUI.SaveGifDialog.message"),
                  Messages.getString("ClientGUI.SaveGifDialog.title"),
                  JOptionPane.YES_NO_OPTION,
                  JOptionPane.INFORMATION_MESSAGE);
            if (response == JOptionPane.YES_OPTION) {
                saveGif();
            }
        }
        deleteGif();
    }

    private void saveGif() {
        SaveDialogResult result = getSaveDialog();
        if ((result.returnVal() != JFileChooser.APPROVE_OPTION) || (result.saveDialog().getSelectedFile() == null)) {
            // without a file there is no saving for the file, which them means we can't save the gif
            LOGGER.warn("No file selected for saving GIF, skipping save operation.");
            return;
        }

        // Did the player select a file?
        File gifFile = result.saveDialog().getSelectedFile();
        if (gifFile != null) {
            if (!gifFile.getName().toLowerCase().endsWith(CG_FILE_EXTENSION_GIF)) {
                try {
                    gifFile = new File(gifFile.getCanonicalPath() + CG_FILE_EXTENSION_GIF);
                } catch (Exception ex) {
                    // without a file there is no saving for the file, which them means we can't save the gif
                    LOGGER.errorDialog(
                          ex, "Unable to get canonical path for file {}", "Error when trying to save GIF",
                          gifFile);
                    return;
                }
            }
            File finalGifFile = gifFile;
            try {
                if (gifWriter.getOutputFile().renameTo(finalGifFile)) {
                    LOGGER.info("Game summary GIF saved to {}", finalGifFile);
                } else {
                    LOGGER.errorDialog("Unable to save GIF in destination",
                          "Unable to save file {} at {}",
                          gifWriter.getOutputFile(),
                          finalGifFile);
                }
            } catch (Exception ex) {
                LOGGER.errorDialog(ex,
                      "Unable to save file {} at {}",
                      "Unable to save GIF in destination",
                      gifWriter.getOutputFile(),
                      finalGifFile);
            }
        }
    }

    private static SaveDialogResult getSaveDialog() {
        String filename = StringUtil.addDateTimeStamp("combat_summary_");
        JFileChooser saveDialog = new JFileChooser(".");
        var frame = JOptionPane.getRootFrame();
        saveDialog.setLocation(frame.getLocation().x + 150, frame.getLocation().y + 100);
        saveDialog.setDialogTitle(Messages.getString("ClientGUI.saveGameSummaryGifFileDialog.title"));
        FileNameExtensionFilter filter = new FileNameExtensionFilter(Messages.getString("ClientGUI.descriptionGIFFiles"),
              CG_FILE_PATH_GIF);
        saveDialog.setFileFilter(filter);

        saveDialog.setSelectedFile(new File(filename + CG_FILE_EXTENSION_GIF));

        int returnVal = saveDialog.showSaveDialog(frame);
        return new SaveDialogResult(saveDialog, returnVal);
    }

    private record SaveDialogResult(JFileChooser saveDialog, int returnVal) {
    }

    private void deleteGif() {
        if (gifWriter.delete()) {
            LOGGER.info("Deleted temporary game summary GIF {}", gifWriter.getOutputFile());
        } else {
            LOGGER.error("Failed to delete temporary game summary GIF {}", gifWriter.getOutputFile());
        }
    }

    /**
     * Stops the thread.
     */
    public void stopThread() {
        stopThread(false);
    }

    public void stopThread(boolean forceInterrupt) {
        isLive = false;
        this.forceInterrupt = forceInterrupt;
        interrupt();
    }
}
