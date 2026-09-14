/*-
 * #%L
 * Multiview stitching of large datasets.
 * %%
 * Copyright (C) 2016 - 2026 Big Stitcher developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.stitcher.plugin;


import java.awt.Button;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.TextField;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.PluginIndex;

import ij.IJ;
import ij.ImageJ;
import ij.Menus;
import ij.plugin.PlugIn;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.io.TextFileAccess;
import net.preibisch.mvrecon.fiji.plugin.Data_Explorer;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration;
import net.preibisch.mvrecon.fiji.plugin.queryXML.GenericLoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.stitcher.gui.StitchingExplorer;

@Plugin(type = Command.class, menuPath = "Plugins>BigStitcher>BigStitcher")
public class BigStitcher implements Command, PlugIn
{
	boolean newDataset = false;

	/** View-count threshold above which the "Large dataset" options dialog appears. */
	private static final int LARGE_DATASET_THRESHOLD = 100;

	@Override
	public void run( String arg )
	{
		run();
	}

	@Override
	public void run( )
	{
		final LoadParseQueryXML result = new EasterEggLoadParseQueryXML();

		result.addButton( "Define a new dataset", new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				((TextField)result.getGenericDialog().getStringFields().firstElement()).setText( "define" );
				Button ok = result.getGenericDialog().getButtons()[ 0 ];

				ActionEvent ae =  new ActionEvent( ok, ActionEvent.ACTION_PERFORMED, "");
				Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(ae);
			}
		});

		result.addCheckbox( "Show_advanced_options" );

		if ( !result.queryXML( "BigStitcher", "", false, false, false, false, false ) && !newDataset )
			return;

		final boolean advanced = result.isCheckBoxSelected();

		final SpimData2 data = result.getData();
		final URI xml = result.getXMLURI();
		final XmlIoSpimData2 io = result.getIO();

		// Warn before unconditionally opening BDV on large datasets — it's slow and often
		// not what the user wants. Below the threshold, behavior is unchanged (BDV opens eagerly).
		boolean openBDV = true;
		final int totalViews = data.getSequenceDescription().getViewSetups().size() * data.getSequenceDescription().getTimePoints().size();
		if ( advanced || totalViews > LARGE_DATASET_THRESHOLD )
		{
			final Boolean openBDVChoice = Data_Explorer.showAdvancedOptionsDialog( totalViews );
			if ( openBDVChoice == null )
				return;
			openBDV = openBDVChoice;
		}

		final StitchingExplorer< SpimData2 > explorer =
				new StitchingExplorer< >( data, xml, io, openBDV );

		explorer.getFrame().toFront();
	}

	public static void setupTesting()
	{
		IOFunctions.printIJLog = true;

		//new net.imagej.ImageJ();
		new ImageJ();
		/*
		try
		{
			final String bigstitcher =
					new File(BigStitcher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();

			System.out.println( "bigstitcher jar: " + bigstitcher );
			installPluginsMenu(bigstitcher);

			System.out.println( );

			final String mvr =
					new File(Interest_Point_Registration.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();

			System.out.println( "multiview-reconstruction jar: " + mvr );
			installPluginsMenu(mvr);

			System.exit( 0 );
		}
		catch (Exception e)
		{
			System.out.println( "Could not load bigstitcher and multiview-reconstruction locations; unable to install menu.");
			e.printStackTrace();
		}

		MenuBar mbar = ij.getMenuBar();
		System.out.println( mbar.getMenuCount() );

		for ( int i = 0; i < mbar.getMenuCount(); ++i )
		{
			Menu menu = mbar.getMenu(i);
			System.out.println( menu );
			if ( menu.getLabel().equals( "Plugins" ) )
			{
				System.out.println( "plugins menu found ...");
				menu.addSeparator();
				menu.add("hallo");
				menu.addSeparator();
			}
		}
		//System.exit(0);
		//if ( System.getProperty("os.name").toLowerCase().contains( "mac" ) )
		//	GenericLoadParseQueryXML.defaultXMLURI = "/Users/preibischs/SparkTest/Stitching/dataset.xml";*/
	}

	public static void main( String[] args )
	{
		setupTesting();
		new BigStitcher().run( );
	}
}
