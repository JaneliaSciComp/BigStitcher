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
package net.preibisch.stitcher.algorithm;

import ij.gui.GenericDialog;
import net.preibisch.mvrecon.Threads;

public class PairwiseStitchingParameters
{
	public double minOverlap;
	public int peaksToCheck;
	public boolean doSubpixel;
	public boolean interpolateCrossCorrelation;
	public boolean showExpertGrouping;
	public boolean useWholeImage;
	public boolean manualNumTasks;
	public int numTasks;

	public PairwiseStitchingParameters()
	{
		this(0, 5, true, false, false, false, false, (int) Math.max( 2, Threads.numThreads() / 6 ));
	}

	public PairwiseStitchingParameters(double minOverlap, int peaksToCheck, boolean doSubpixel,
		boolean interpolateCrossCorrelation, boolean showExpertGrouping)
	{
		this(minOverlap, peaksToCheck, doSubpixel, interpolateCrossCorrelation, showExpertGrouping, false, false, (int)  Math.max( 2, Threads.numThreads() / 6 ));
	}

	public PairwiseStitchingParameters(double minOverlap, int peaksToCheck, boolean doSubpixel,
			boolean interpolateCrossCorrelation, boolean showExpertGrouping, boolean useWholeImage,
			boolean manualNumTaksks, int numTasks)
	{
		this.minOverlap = minOverlap;
		this.peaksToCheck = peaksToCheck;
		this.doSubpixel = doSubpixel;
		this.interpolateCrossCorrelation = interpolateCrossCorrelation;
		this.showExpertGrouping = showExpertGrouping;
		this.useWholeImage = useWholeImage;
		this.manualNumTasks = manualNumTaksks;
		this.numTasks = numTasks;
	}

	public static void addQueriesToGD(final GenericDialog gd)
	{
		gd.addNumericField( "number_of_peaks_to_check", 5, 0 );
		gd.addNumericField( "minimal_overlap (percent of current overlap)", 0, 0 );
		gd.addCheckbox( "subpixel_accuracy", true );
		gd.addCheckbox( "interpolate_subpixel_cross_correlation (warning: slow!)", false );
		gd.addCheckbox( "use_whole_image (warning: slow!)", false );
		gd.addCheckbox( "manually_set_number_of_parallel_tasks", false );
		gd.addNumericField( "number_of_parallel_tasks", (int) Math.max( 2, Threads.numThreads() / 6 ), 0 );
		gd.addCheckbox( "show_expert_grouping_options", false );
	}

	public static PairwiseStitchingParameters getParametersFromGD(final GenericDialog gd)
	{
		if (gd.wasCanceled())
			return null;

		int peaksToCheck  = (int) gd.getNextNumber();
		double minOverlap =  Math.min( Math.max( gd.getNextNumber()/100 , 0), 1);
		boolean doSubpixel = gd.getNextBoolean();
		boolean interpolateSubpixel = gd.getNextBoolean();
		boolean useWholeImage = gd.getNextBoolean();
		boolean manualNumTasks = gd.getNextBoolean();
		int numTasks = (int) (manualNumTasks ? gd.getNextNumber() : Math.max( 2, Threads.numThreads() / 6 ));
		boolean showExpertGrouping = gd.getNextBoolean();

		return new PairwiseStitchingParameters(minOverlap, peaksToCheck, doSubpixel, interpolateSubpixel, showExpertGrouping, useWholeImage, manualNumTasks, numTasks);
	}

	public static PairwiseStitchingParameters askUserForParameters()
	{
		// ask user for parameters
		GenericDialog gd = new GenericDialog("Pairwise stitching options");
		addQueriesToGD( gd );

		gd.showDialog();
		return getParametersFromGD( gd );
	}
}
