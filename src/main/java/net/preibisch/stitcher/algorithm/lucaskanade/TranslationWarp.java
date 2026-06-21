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
package net.preibisch.stitcher.algorithm.lucaskanade;

import net.imglib2.RealLocalizable;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;

public class TranslationWarp implements WarpFunction
{
	final int n;

	public TranslationWarp(int nDimensions)
	{
		this.n = nDimensions;
	}
	@Override
	public int numParameters()
	{
		return n;
	}

	@Override
	public double partial(RealLocalizable pos, int d, int param)
	{
		return (d == param) ? 1.0 : 0.0;
	}

	@Override
	public AffineGet getAffine(double[] p)
	{
		final AffineTransform3D res = new AffineTransform3D();
		for (int i = 0; i < n; ++i)
			res.set( p[i], i, n );
		return res;
	}

}
