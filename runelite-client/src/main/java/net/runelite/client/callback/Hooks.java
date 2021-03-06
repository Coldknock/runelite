/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.callback;

import com.google.common.eventbus.EventBus;
import com.google.inject.Injector;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import net.runelite.api.Client;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.RenderOverview;
import net.runelite.api.WorldMapManager;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import static net.runelite.api.widgets.WidgetInfo.WORLD_MAP_VIEW;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLite;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.task.Scheduler;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayRenderer;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.DeferredEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class contains field required for mixins and runelite hooks to work.
 * All remaining method hooks in this class are performance-critical or contain client-specific logic and so they
 * can't just be placed in mixins or sent through event bus.
 */
public class Hooks
{
	// must be public as the mixins use it
	public static final Logger log = LoggerFactory.getLogger(Hooks.class);

	private static final long CHECK = 600; // ms - how often to run checks

	private static final Injector injector = RuneLite.getInjector();
	private static final Client client = injector.getInstance(Client.class);
	public static final EventBus eventBus = injector.getInstance(EventBus.class); // symbol must match mixin Hook
	private static final DeferredEventBus _deferredEventBus = injector.getInstance(DeferredEventBus.class);
	public static final EventBus deferredEventBus = _deferredEventBus; // symbol must match mixin Hook
	private static final Scheduler scheduler = injector.getInstance(Scheduler.class);
	private static final InfoBoxManager infoBoxManager = injector.getInstance(InfoBoxManager.class);
	private static final ChatMessageManager chatMessageManager = injector.getInstance(ChatMessageManager.class);
	private static final OverlayRenderer renderer = injector.getInstance(OverlayRenderer.class);
	private static final MouseManager mouseManager = injector.getInstance(MouseManager.class);
	private static final KeyManager keyManager = injector.getInstance(KeyManager.class);
	private static final ClientThread clientThread = injector.getInstance(ClientThread.class);
	private static final GameTick tick = new GameTick();
	private static final DrawManager renderHooks = injector.getInstance(DrawManager.class);
	private static final Notifier notifier = injector.getInstance(Notifier.class);
	private static final ClientUI clientUi = injector.getInstance(ClientUI.class);

	private static Dimension lastStretchedDimensions;
	private static VolatileImage stretchedImage;
	private static Graphics2D stretchedGraphics;

	private static long lastCheck;
	private static boolean shouldProcessGameTick;

	public static void clientMainLoop()
	{
		if (shouldProcessGameTick)
		{
			shouldProcessGameTick = false;

			_deferredEventBus.replay();

			eventBus.post(tick);

			int tick = client.getTickCount();
			client.setTickCount(tick + 1);
		}

		clientThread.invoke();

		long now = System.currentTimeMillis();

		if (now - lastCheck < CHECK)
		{
			return;
		}

		lastCheck = now;

		try
		{
			// tick pending scheduled tasks
			scheduler.tick();

			// cull infoboxes
			infoBoxManager.cull();

			chatMessageManager.process();

			checkWorldMap();
		}
		catch (Exception ex)
		{
			log.warn("error during main loop tasks", ex);
		}
	}

	/**
	 * When the world map opens it loads about ~100mb of data into memory, which
	 * represents about half of the total memory allocated by the client.
	 * This gets cached and never released, which causes GC pressure which can affect
	 * performance. This method reinitailzies the world map cache, which allows the
	 * data to be garbage collecged, and causes the map data from disk each time
	 * is it opened.
	 */
	private static void checkWorldMap()
	{
		Widget widget = client.getWidget(WORLD_MAP_VIEW);

		if (widget != null)
		{
			return;
		}

		RenderOverview renderOverview = client.getRenderOverview();

		if (renderOverview == null)
		{
			return;
		}

		WorldMapManager manager = renderOverview.getWorldMapManager();

		if (manager != null && manager.isLoaded())
		{
			log.debug("World map was closed, reinitializing");
			renderOverview.initializeWorldMap(renderOverview.getWorldMapData());
		}
	}

	public static MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		return mouseManager.processMousePressed(mouseEvent);
	}

	public static MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		return mouseManager.processMouseReleased(mouseEvent);
	}

	public static MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		return mouseManager.processMouseClicked(mouseEvent);
	}

	public static MouseEvent mouseEntered(MouseEvent mouseEvent)
	{
		return mouseManager.processMouseEntered(mouseEvent);
	}

	public static MouseEvent mouseExited(MouseEvent mouseEvent)
	{
		return mouseManager.processMouseExited(mouseEvent);
	}

	public static MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		return mouseManager.processMouseDragged(mouseEvent);
	}

	public static MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		return mouseManager.processMouseMoved(mouseEvent);
	}

	public static MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		return mouseManager.processMouseWheelMoved(event);
	}

	public static void keyPressed(KeyEvent keyEvent)
	{
		keyManager.processKeyPressed(keyEvent);
	}

	public static void keyReleased(KeyEvent keyEvent)
	{
		keyManager.processKeyReleased(keyEvent);
	}

	public static void keyTyped(KeyEvent keyEvent)
	{
		keyManager.processKeyTyped(keyEvent);
	}

	public static void draw(MainBufferProvider mainBufferProvider, Graphics graphics, int x, int y)
	{
		if (graphics == null)
		{
			return;
		}

		Image image = mainBufferProvider.getImage();
		final Graphics2D graphics2d = (Graphics2D) image.getGraphics();

		try
		{
			renderer.render(graphics2d, OverlayLayer.ALWAYS_ON_TOP);
		}
		catch (Exception ex)
		{
			log.warn("Error during overlay rendering", ex);
		}

		notifier.processFlash(graphics2d);

		// Stretch the game image if the user has that enabled
		if (!client.isResized() && client.isStretchedEnabled())
		{
			GraphicsConfiguration gc = clientUi.getGraphicsConfiguration();
			Dimension stretchedDimensions = client.getStretchedDimensions();

			if (lastStretchedDimensions == null || !lastStretchedDimensions.equals(stretchedDimensions)
				|| (stretchedImage != null && stretchedImage.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE))
			{
				/*
					Reuse the resulting image instance to avoid creating an extreme amount of objects
				 */
				stretchedImage = gc.createCompatibleVolatileImage(stretchedDimensions.width, stretchedDimensions.height);

				if (stretchedGraphics != null)
				{
					stretchedGraphics.dispose();
				}
				stretchedGraphics = (Graphics2D) stretchedImage.getGraphics();

				lastStretchedDimensions = stretchedDimensions;

				/*
					Fill Canvas before drawing stretched image to prevent artifacts.
				*/
				graphics.setColor(Color.BLACK);
				graphics.fillRect(0, 0, client.getCanvas().getWidth(), client.getCanvas().getHeight());
			}

			stretchedGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					client.isStretchedFast()
							? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
							: RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			stretchedGraphics.drawImage(image, 0, 0, stretchedDimensions.width, stretchedDimensions.height, null);

			image = stretchedImage;
		}

		// Draw the image onto the game canvas
		graphics.drawImage(image, 0, 0, client.getCanvas());

		renderHooks.processDrawComplete(image);
	}

	public static void drawRegion()
	{
		MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
		BufferedImage image = (BufferedImage) bufferProvider.getImage();
		Graphics2D graphics2d = (Graphics2D) image.getGraphics();

		try
		{
			renderer.render(graphics2d, OverlayLayer.ABOVE_SCENE);
		}
		catch (Exception ex)
		{
			log.warn("Error during overlay rendering", ex);
		}
	}

	public static void drawAboveOverheads()
	{
		MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
		BufferedImage image = (BufferedImage) bufferProvider.getImage();
		Graphics2D graphics2d = (Graphics2D) image.getGraphics();

		try
		{
			renderer.render(graphics2d, OverlayLayer.UNDER_WIDGETS);
		}
		catch (Exception ex)
		{
			log.warn("Error during overlay rendering", ex);
		}
	}

	public static void drawAfterWidgets()
	{
		MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
		BufferedImage image = (BufferedImage) bufferProvider.getImage();
		Graphics2D graphics2d = (Graphics2D) image.getGraphics();

		try
		{
			renderer.render(graphics2d, OverlayLayer.ABOVE_WIDGETS);
		}
		catch (Exception ex)
		{
			log.warn("Error during overlay rendering", ex);
		}
	}

	public static void updateNpcs()
	{
		// The NPC update event seem to run every server tick,
		// but having the game tick event after all packets
		// have been processed is typically more useful.
		shouldProcessGameTick = true;
	}
}
