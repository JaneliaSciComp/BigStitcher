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

import net.imglib2.Point;
import net.imglib2.RealLocalizable;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.util.Util;

public class RigidWarp2D implements WarpFunction
{

	@Override
	public int numParameters()
	{
		return 3;
	}

	@Override
	public double partial(RealLocalizable pos, int d, int param)
	{
		
		if (param == 0)
			return (d == 0 ? -1.0 : 1.0) * pos.getDoublePosition( (d + 1) % 2 );

		else
			return d == param-1 ? 1.0 : 0.0;
	}

	@Override
	public AffineGet getAffine(double[] p)
	{
		final AffineTransform res = new AffineTransform(2);
		res.set( Math.cos( p[0]), 0, 0);
		res.set( - Math.sin( p[0]), 0, 1);
		res.set( p[1], 0, 2);
		res.set( Math.sin( p[0] ), 1, 0 );
		res.set( Math.cos( p[0] ), 1, 1);
		res.set( p[2], 1, 2 );
		return res.copy();
	}
	
	public static void main(String[] args)
	{
		RigidWarp aw = new RigidWarp( 2 );
		System.out.println( Util.printCoordinates( aw.getAffine( new double[] {Math.PI / 2, 0, 0} ).getRowPackedCopy() ) );
		
		for (int d = 0; d<2; d++)
			for (int p = 0; p<3; p++)
				System.out.println( aw.partial( new Point( 2,3 ), d, p ) );
	}

}
